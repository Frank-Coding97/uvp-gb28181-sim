package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.AuxFunction
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PresetOp
import com.uvp.sim.gb28181.PtzCmdDecoder
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.PtzInstruction
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * 引擎层副作用接口,Dispatcher 通过它向上层(SimulatorEngine)请求执行
 * commonMain 无法独立完成的动作:重启注册、抓拍上报、强制 IDR 等.
 *
 * 测试可注入 fake 实现,无需真实 engine / camera.
 */
interface DeviceControlActions {
    /** TeleBoot — 重启设备(Engine 端会 unregister + delay + register). */
    suspend fun reboot()
    /** SnapShotCmd — 触发抓拍 + 上报(走已有 reportSnapshot 流程,7.4 旧路径). */
    suspend fun snapshot()
    /** IFameCmd — 强制下一帧出 IDR. */
    fun requestKeyFrame()
    /** SnapShotConfig — GB-2022 §9.5 平台下发的图像抓拍配置(7.5 新路径,委托 SnapshotUploadEngine). */
    suspend fun triggerSnapshotConfig(cfg: com.uvp.sim.gb28181.SnapShotConfig)
    /** GB-2022 §9.13 DeviceUpgrade 在线升级 — 启动假进度协程,5s 内推 4 条 NOTIFY (0/30/60/100). */
    fun startUpgrade(sessionId: String, firmware: String, fileUrl: String)
}

/**
 * Dispatcher 处理 DeviceControl 后返回给 SimulatorEngine 的应答指引。
 *
 * - [needSipResponse] 是否需要回 SIP 200 OK(DeviceControl 一律 true,即使解析失败,
 *   避免平台重试)
 * - [alarmReset] 本次命令是否触发了报警复位(AlarmCmd 0/2/ResetAlarm)
 * - [by] 复位来源(平台 fromUri),供 emit AlarmReset(Remote) 用
 */
data class DeviceControlAck(
    val needSipResponse: Boolean = true,
    val alarmReset: Boolean = false,
    val by: String? = null
)

/**
 * GB/T 28181-2022 §F.3 DeviceControl 命令分发器.
 *
 * 输入:平台 SIP MESSAGE 中的 MANSCDP XML 体.
 * 输出:更新 [DeviceControlState],并通过 [DeviceControlActions] 触发副作用.
 *
 * 子命令覆盖 (plan §2.1.2):
 * 1. PTZCmd        — 8 字节解码 → 速率写入 state
 * 2. IFameCmd      — 强制 IDR + 镜头闪光
 * 3. TeleBoot      — 重启设备 + LED 重启动画
 * 4. RecordCmd     — 切换录像状态
 * 5. GuardCmd      — 切换布防状态
 * 6. AlarmCmd      — 复位报警灯 + 返回 alarmReset ack
 * 7. DragZoomIn/Out — 平台拉框聚焦,记录矩形
 * 8. HomePosition  — 预置位 set/recall
 * 9. BasicParam    — DeviceConfig 在线修改
 * 10. SnapShotCmd  — 抓拍上报
 *
 * 设计:轻量 tag-fishing 解析(沿用 ManscdpParser 风格),不引入 XML 库.
 * scope 用于 fire-and-forget suspend 副作用(reboot / snapshot).
 */
class DeviceControlDispatcher(
    private val state: MutableStateFlow<DeviceControlState>,
    private val config: SimConfig,
    private val actions: DeviceControlActions,
    private val scope: CoroutineScope? = null,
) {

    companion object {
        /** 预置位上限(spec Q3:行业惯例 1-8;越界一律 200 OK 但忽略业务). */
        const val MAX_PRESET_INDEX = 8
    }

    /**
     * 主入口.根据 XML 中第一个匹配的子命令标签分发.
     * 一个 DeviceControl 体里通常只含一个子命令,这里按已知顺序逐个尝试,
     * 命中即停;避免一次处理多个语义冲突的命令.
     *
     * 返回 [DeviceControlAck] 让 engine 决定是否回 200 OK / 是否联动报警复位。
     * [fromUri] 是平台 MESSAGE 的 From URI,仅 AlarmCmd 复位时透传给 ack.by。
     */
    fun dispatch(xml: String, fromUri: String? = null): DeviceControlAck {
        if (xml.isBlank()) return DeviceControlAck(needSipResponse = false)

        return when {
            ManscdpParser.tagValue(xml, "PTZCmd") != null -> { handlePtz(xml); DeviceControlAck() }
            // GB-2022 §9.3.4 A.2.3.1.11 精确云台 — 优先于其他控制命令匹配
            xml.contains("<PTZPreciseCtrl>") -> { handlePtzPrecise(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "IFameCmd") != null -> { handleIFrame(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "TeleBoot") != null -> { handleTeleBoot(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "RecordCmd") != null -> { handleRecord(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "GuardCmd") != null -> { handleGuard(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "AlarmCmd") != null -> handleAlarm(xml, fromUri)
            xml.contains("<DragZoomIn>") || xml.contains("<DragZoomOut>") -> { handleDragZoom(xml); DeviceControlAck() }
            xml.contains("<HomePosition>") -> { handleHomePosition(xml); DeviceControlAck() }
            xml.contains("<BasicParam>") -> { handleDeviceConfig(xml); DeviceControlAck() }
            // GB-2022 §9.3.4 新增项 — 200 OK + UI snackbar 提示,不真做业务
            xml.contains("<DeviceUpgrade>") -> { handleDeviceUpgrade(xml); DeviceControlAck() }
            xml.contains("<FormatSDCard>") -> { handleFormatSDCard(xml); DeviceControlAck() }
            xml.contains("<TargetTrack>") -> { handleTargetTrack(xml); DeviceControlAck() }
            // GB-2022 §9.5 图像抓拍 — 7.5 新路径,优先于 7.4 旧 SnapShotCmd 匹配
            xml.contains("<SnapShotConfig>") -> { handleSnapShotConfig(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "SnapShotCmd") != null -> { handleSnapshot(xml); DeviceControlAck() }
            else -> DeviceControlAck(needSipResponse = false)
        }
    }

    // ---------- PTZ ----------

    private fun handlePtz(xml: String) {
        val hex = ManscdpParser.tagValue(xml, "PTZCmd") ?: return
        when (val ins = PtzCmdDecoder.decodeInstruction(hex)) {
            is PtzInstruction.Motion -> handlePtzMotion(ins.cmd, hex)
            is PtzInstruction.Preset -> handlePtzPreset(ins, hex)
            is PtzInstruction.Aux -> handlePtzAux(ins, hex)
            is PtzInstruction.Cruise -> handlePtzCruise(ins, hex)
            null -> { /* 校验失败 / 未知子族,200 OK 由 ack 兜底 */ }
        }
    }

    private fun handlePtzMotion(ptz: PtzCommand, hex: String) {
        state.update {
            // Focus 累计:WVP 按住按钮每秒发 ~5-10 条,每条 +/- 一个 step.
            // step = focusSpeed * 0.005,speed=15 → 0.075/cmd,speed=1 → 0.005/cmd.
            val focusDelta = when (ptz.focusDirection) {
                com.uvp.sim.gb28181.FocusDirection.NEAR -> -ptz.focusSpeed * 0.005f
                com.uvp.sim.gb28181.FocusDirection.FAR -> ptz.focusSpeed * 0.005f
                com.uvp.sim.gb28181.FocusDirection.NONE -> 0f
            }
            val newFocusLevel = (it.focusLevel + focusDelta).coerceIn(0f, 1f)
            // Iris 累计:跟 focus 同步累加机制
            val irisDelta = when (ptz.irisDirection) {
                com.uvp.sim.gb28181.IrisDirection.OPEN -> ptz.irisSpeed * 0.005f
                com.uvp.sim.gb28181.IrisDirection.CLOSE -> -ptz.irisSpeed * 0.005f
                com.uvp.sim.gb28181.IrisDirection.NONE -> 0f
            }
            val newIrisLevel = (it.irisLevel + irisDelta).coerceIn(0f, 1f)
            it.copy(
                panSpeed = mapPanSpeed(ptz),
                tiltSpeed = mapTiltSpeed(ptz),
                zoomSpeed = mapZoomSpeed(ptz),
                focusLevel = newFocusLevel,
                irisLevel = newIrisLevel,
                lastCommand = LastDeviceCommand("PTZCmd", hex, nowMs(), ptz)
            )
        }
    }

    /** 巡航 CRUD (GB-2022 §F.3 byte3=0x84-0x88). */
    private fun handlePtzCruise(p: PtzInstruction.Cruise, hex: String) {
        val now = nowMs()
        when (p.op) {
            com.uvp.sim.gb28181.CruiseOp.SET_POINT -> {
                // 把预置位 p.param 加入巡航轨迹 p.trackNum
                state.update { s ->
                    val track = s.cruiseTracks[p.trackNum].orEmpty()
                    val updated = if (p.param in track) track else track + p.param
                    s.copy(
                        cruiseTracks = s.cruiseTracks + (p.trackNum to updated),
                        lastCommand = LastDeviceCommand("PTZCmd", "巡航 #${p.trackNum} 添加 P${p.param}", now)
                    )
                }
            }
            com.uvp.sim.gb28181.CruiseOp.DEL_POINT -> {
                state.update { s ->
                    val track = s.cruiseTracks[p.trackNum].orEmpty()
                    val updated = track - p.param
                    s.copy(
                        cruiseTracks = if (updated.isEmpty()) s.cruiseTracks - p.trackNum
                            else s.cruiseTracks + (p.trackNum to updated),
                        lastCommand = LastDeviceCommand("PTZCmd", "巡航 #${p.trackNum} 删除 P${p.param}", now)
                    )
                }
            }
            com.uvp.sim.gb28181.CruiseOp.SET_SPEED -> {
                state.update { it.copy(lastCommand = LastDeviceCommand("PTZCmd", "巡航 #${p.trackNum} 速度=${p.param}", now)) }
            }
            com.uvp.sim.gb28181.CruiseOp.SET_DWELL_TIME -> {
                state.update { it.copy(lastCommand = LastDeviceCommand("PTZCmd", "巡航 #${p.trackNum} 停留=${p.param}s", now)) }
            }
            com.uvp.sim.gb28181.CruiseOp.START -> {
                state.update {
                    it.copy(
                        activeCruiseTrack = if (p.trackNum == 0) null else p.trackNum,
                        lastCommand = LastDeviceCommand(
                            "PTZCmd",
                            if (p.trackNum == 0) "巡航停止" else "巡航 #${p.trackNum} 启动",
                            now
                        )
                    )
                }
            }
        }
    }

    /**
     * 预置位 CRUD (GB-2022 §F.3 byte3 = 0x81/0x82/0x83 + byte4 = 编号).
     *
     * - 上限 8 个,index 范围 1-8(spec Q3 决议),越界仅记 lastCommand 不动 presets
     * - SET: 当前姿态入库(可覆盖)+ 设 currentPresetIndex
     * - CALL: 已存在则 emit [DeviceEffect.PresetRecall];不存在仅记 lastCommand
     * - DEL: 移除;若删除的正是 currentPresetIndex 则清零
     */
    private fun handlePtzPreset(p: PtzInstruction.Preset, hex: String) {
        val idx = p.index
        if (idx !in 1..MAX_PRESET_INDEX) {
            state.update {
                it.copy(
                    lastCommand = LastDeviceCommand(
                        "PTZCmd", "${p.op}#$idx (out-of-range)", nowMs()
                    )
                )
            }
            return
        }
        state.update { s ->
            when (p.op) {
                PresetOp.SET -> {
                    val pose = PtzPose(s.panAngle, s.tiltAngle, s.zoomLevel)
                    s.copy(
                        presets = s.presets + (idx to pose),
                        currentPresetIndex = idx,
                        lastCommand = LastDeviceCommand("PTZCmd", "SetPreset#$idx", nowMs())
                    )
                }
                PresetOp.CALL -> {
                    val target = s.presets[idx]
                    if (target == null) {
                        s.copy(
                            lastCommand = LastDeviceCommand(
                                "PTZCmd", "CallPreset#$idx (empty)", nowMs()
                            )
                        )
                    } else {
                        s.copy(
                            currentPresetIndex = idx,
                            pendingEffect = DeviceEffect.PresetRecall(idx, target),
                            lastCommand = LastDeviceCommand("PTZCmd", "CallPreset#$idx", nowMs())
                        )
                    }
                }
                PresetOp.DEL -> s.copy(
                    presets = s.presets - idx,
                    currentPresetIndex = if (s.currentPresetIndex == idx) null else s.currentPresetIndex,
                    lastCommand = LastDeviceCommand("PTZCmd", "DelPreset#$idx", nowMs())
                )
            }
        }
    }

    /** GB28181 速度字节 0-255 → ±90°/s 线性映射;方向 NONE → 0. */
    private fun mapPanSpeed(p: PtzCommand): Float = when (p.panDirection) {
        PanDirection.LEFT -> -p.panSpeed * 90f / 255f
        PanDirection.RIGHT -> p.panSpeed * 90f / 255f
        PanDirection.NONE -> 0f
    }

    private fun mapTiltSpeed(p: PtzCommand): Float = when (p.tiltDirection) {
        TiltDirection.UP -> p.tiltSpeed * 90f / 255f
        TiltDirection.DOWN -> -p.tiltSpeed * 90f / 255f
        TiltDirection.NONE -> 0f
    }

    /** Zoom 速度 0-15 → ±1x/s. */
    private fun mapZoomSpeed(p: PtzCommand): Float = when (p.zoomDirection) {
        ZoomDirection.IN -> p.zoomSpeed / 15f
        ZoomDirection.OUT -> -p.zoomSpeed / 15f
        ZoomDirection.NONE -> 0f
    }

    /**
     * 辅助控制 (GB-2022 §F.3 byte3=0x89/0x8A).
     *
     * 编号映射(海康/大华事实标准):1=雨刷 / 2=红外灯 / 3=加热 / 4=除雾 / 5=制冷.
     * 未知 index 仍记 lastCommand 但不动 auxStates.
     */
    private fun handlePtzAux(p: PtzInstruction.Aux, hex: String) {
        val func = AuxFunction.fromIndex(p.index)
        val name = func?.displayName ?: "Aux${p.index}"
        val opLabel = if (p.on) "ON" else "OFF"
        val now = nowMs()
        if (func != null) {
            state.update {
                it.copy(
                    auxStates = it.auxStates + (p.index to p.on),
                    auxTimestamps = it.auxTimestamps + (p.index to now),
                    lastCommand = LastDeviceCommand("PTZCmd", "$name $opLabel", now)
                )
            }
        } else {
            state.update {
                it.copy(
                    lastCommand = LastDeviceCommand("PTZCmd", "Aux#${p.index} $opLabel (unmapped)", now)
                )
            }
        }
    }

    // ---------- IFrame ----------

    private fun handleIFrame(xml: String) {
        val v = ManscdpParser.tagValue(xml, "IFameCmd") ?: return
        if (!v.equals("Send", ignoreCase = true)) return
        actions.requestKeyFrame()
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.IFrameFlash,
                lastCommand = LastDeviceCommand("IFameCmd", v, nowMs())
            )
        }
    }

    // ---------- TeleBoot ----------

    private fun handleTeleBoot(xml: String) {
        state.update {
            it.copy(
                isRebooting = true,
                pendingEffect = DeviceEffect.Reboot,
                lastCommand = LastDeviceCommand("TeleBoot", "Boot", nowMs())
            )
        }
        scope?.launch { actions.reboot() }
    }

    // ---------- Record ----------

    private fun handleRecord(xml: String) {
        val v = ManscdpParser.tagValue(xml, "RecordCmd") ?: return
        val on = v.equals("Record", ignoreCase = true)
        state.update {
            it.copy(
                isRecording = on,
                lastCommand = LastDeviceCommand("RecordCmd", v, nowMs())
            )
        }
    }

    // ---------- Guard ----------

    private fun handleGuard(xml: String) {
        val v = ManscdpParser.tagValue(xml, "GuardCmd") ?: return
        val on = v.equals("SetGuard", ignoreCase = true)
        state.update {
            it.copy(
                isGuarded = on,
                lastCommand = LastDeviceCommand("GuardCmd", v, nowMs())
            )
        }
    }

    // ---------- Alarm ----------

    /**
     * GB §9.3.4 AlarmCmd 反向控制。取值兼容两种平台风格:
     *   - 数值 0(复位) / 1(布防) / 2(撤防)
     *   - 字符串 "ResetAlarm"(部分平台用文字)
     *
     * 0 / 2 / ResetAlarm → 复位(isAlarming=false + alarmReset ack)
     * 1                  → 布防,仅记录,不切 isAlarming(布防归 GuardCmd,spec 非目标)
     * 其他未知值         → 不切 isAlarming,仍回 200(避免平台重试)
     */
    private fun handleAlarm(xml: String, fromUri: String?): DeviceControlAck {
        val raw = ManscdpParser.tagValue(xml, "AlarmCmd")
            ?: return DeviceControlAck(needSipResponse = true)
        val numeric = raw.toIntOrNull()
        val isReset = numeric == 0 || numeric == 2 || raw.equals("ResetAlarm", ignoreCase = true)
        return if (isReset) {
            state.update {
                it.copy(
                    isAlarming = false,
                    lastCommand = LastDeviceCommand("AlarmCmd", raw, nowMs())
                )
            }
            DeviceControlAck(needSipResponse = true, alarmReset = true, by = fromUri)
        } else {
            // 布防(1)或未知值:记录命令但不切 isAlarming
            state.update {
                it.copy(lastCommand = LastDeviceCommand("AlarmCmd", raw, nowMs()))
            }
            DeviceControlAck(needSipResponse = true, alarmReset = false, by = fromUri)
        }
    }

    // ---------- DragZoom ----------

    private fun handleDragZoom(xml: String) {
        val midX = ManscdpParser.tagValue(xml, "MidPointX")?.toIntOrNull() ?: return
        val midY = ManscdpParser.tagValue(xml, "MidPointY")?.toIntOrNull() ?: return
        val lengthX = ManscdpParser.tagValue(xml, "LengthX")?.toIntOrNull() ?: 0
        val lengthY = ManscdpParser.tagValue(xml, "LengthY")?.toIntOrNull() ?: 0
        val type = if (xml.contains("<DragZoomIn>")) "DragZoomIn" else "DragZoomOut"
        state.update {
            it.copy(
                dragZoomRect = DragZoomRect(midX, midY, lengthX, lengthY),
                lastCommand = LastDeviceCommand(type, "($midX,$midY) ${lengthX}x$lengthY", nowMs())
            )
        }
    }

    // ---------- HomePosition ----------

    private fun handleHomePosition(xml: String) {
        val enabledRaw = ManscdpParser.tagValue(xml, "Enabled")
        val idx = ManscdpParser.tagValue(xml, "PresetIndex")?.toIntOrNull() ?: return
        // Enabled=0 → 关闭看守位
        if (enabledRaw == "0") {
            state.update {
                it.copy(
                    homePositionEnabled = false,
                    lastCommand = LastDeviceCommand("HomePosition", "Disabled", nowMs())
                )
            }
            return
        }
        val existing = state.value.presets[idx]
        if (existing == null) {
            val now = state.value
            val pose = PtzPose(now.panAngle, now.tiltAngle, now.zoomLevel)
            state.update {
                it.copy(
                    presets = it.presets + (idx to pose),
                    currentPresetIndex = idx,
                    homePosition = pose,             // 同步写看守位字段供 Query 应答
                    homePositionEnabled = true,
                    lastCommand = LastDeviceCommand("HomePosition", "Set#$idx", nowMs())
                )
            }
        } else {
            state.update {
                it.copy(
                    currentPresetIndex = idx,
                    homePosition = existing,
                    homePositionEnabled = true,
                    pendingEffect = DeviceEffect.HomePositionReturn(existing),
                    lastCommand = LastDeviceCommand("HomePosition", "Recall#$idx", nowMs())
                )
            }
        }
    }

    // ---------- DeviceConfig ----------

    private fun handleDeviceConfig(xml: String) {
        val changed = mutableListOf<String>()
        ManscdpParser.tagValue(xml, "Name")?.let { changed += "Name" }
        ManscdpParser.tagValue(xml, "HeartBeatInterval")?.let { changed += "HeartBeatInterval" }
        ManscdpParser.tagValue(xml, "HeartBeatCount")?.let { changed += "HeartBeatCount" }
        ManscdpParser.tagValue(xml, "Expiration")?.let { changed += "Expiration" }
        if (changed.isEmpty()) return
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.ConfigChanged(changed),
                lastCommand = LastDeviceCommand("DeviceConfig", changed.joinToString(","), nowMs())
            )
        }
    }

    // ---------- GB-2022 §9.3.4 新增项 ----------

    /** A.2.3.1.11 PTZPreciseCtrl — 精确云台控制(必做). */
    private fun handlePtzPrecise(xml: String) {
        val p = com.uvp.sim.gb28181.PtzPreciseCtrlParser.parse(xml) ?: return
        val target = PtzPose(p.pan, p.tilt, p.zoom)
        state.update {
            it.copy(
                lastPreciseCtrl = target,
                pendingEffect = DeviceEffect.PrecisePoseGoto(target),
                lastCommand = LastDeviceCommand(
                    "PTZPreciseCtrl",
                    "${p.pan},${p.tilt},${p.zoom}x",
                    nowMs()
                )
            )
        }
    }

    /** A.2.3.1.12 DeviceUpgrade — 在线升级,模拟 5s 假进度并推 NOTIFY 给平台. */
    private fun handleDeviceUpgrade(xml: String) {
        val firmware = ManscdpParser.tagValue(xml, "Firmware") ?: "(unknown)"
        val sessionId = ManscdpParser.tagValue(xml, "SessionID") ?: "auto-${nowMs()}"
        val fileUrl = ManscdpParser.tagValue(xml, "FileURL") ?: ""
        state.update {
            it.copy(
                upgradeProgress = UpgradeProgress(
                    sessionId = sessionId,
                    firmware = firmware,
                    percent = 0,
                    result = UpgradeResult.InProgress,
                ),
                pendingEffect = DeviceEffect.DeviceUpgradeRequested(firmware),
                lastCommand = LastDeviceCommand("DeviceUpgrade", firmware, nowMs())
            )
        }
        actions.startUpgrade(sessionId, firmware, fileUrl)
    }

    /** A.2.3.1.13 FormatSDCard — 格式化 SD 卡(选做最小集). 手机无 SD 卡概念,只为协议合规.
     *
     *  M5 batch3 §4.13 字段补全:GB-2022 标准是 `<FormatSDCard>1</FormatSDCard><DiskNum>N</DiskNum>`,
     *  优先读 `<DiskNum>`(真实卡号),fallback 读 `<FormatSDCard>` 的整数(老格式兼容)。
     */
    private fun handleFormatSDCard(xml: String) {
        val diskNum = ManscdpParser.tagValue(xml, "DiskNum")?.toIntOrNull()
        val card = diskNum ?: ManscdpParser.tagValue(xml, "FormatSDCard")?.toIntOrNull() ?: 0
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.FormatSDCardRequested(card),
                lastCommand = LastDeviceCommand("FormatSDCard", "card $card", nowMs())
            )
        }
    }

    /** A.2.3.1.14 TargetTrack — 目标跟踪. 鱼眼/全景球机专用,sim 白名单识别 mode 不做业务.
     *
     *  M5 batch3 §4.14 字段补全:GB-2022 标准是 `<Mode>Auto|Manual|Stop</Mode>` + 可选
     *  `<ObjectID>...</ObjectID>` + `<Speed>1-255</Speed>`。老格式兼容 `<TargetTrack>Auto</TargetTrack>`。
     *  mode 不在白名单 → warn 不写 lastCommand(平台仍收 200 由外层路由保证)。
     */
    private fun handleTargetTrack(xml: String) {
        val mode = ManscdpParser.tagValue(xml, "Mode")
            ?: ManscdpParser.tagValue(xml, "TargetTrack")
            ?: "Auto"
        if (mode !in setOf("Auto", "Manual", "Stop")) {
            // 不更新 lastCommand,但外层 dispatch 已返回 DeviceControlAck → 仍回 200 OK
            return
        }
        val objectId = ManscdpParser.tagValue(xml, "ObjectID")
        val speed = ManscdpParser.tagValue(xml, "Speed")?.toIntOrNull()
        val detail = buildString {
            append("mode=").append(mode)
            objectId?.let { append(" obj=").append(it) }
            speed?.let { append(" speed=").append(it) }
        }
        state.update {
            it.copy(lastCommand = LastDeviceCommand("TargetTrack", detail, nowMs()))
        }
    }

    // ---------- Snapshot ----------

    private fun handleSnapshot(xml: String) {
        val v = ManscdpParser.tagValue(xml, "SnapShotCmd") ?: return
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.SnapshotFlash,
                lastCommand = LastDeviceCommand("SnapShotCmd", v, nowMs())
            )
        }
        scope?.launch { actions.snapshot() }
    }

    // ---------- SnapShotConfig (GB-2022 §9.5 7.5 新路径) ----------

    /**
     * 解析平台下发的图像抓拍配置,委托 SnapshotUploadEngine 异步执行序列。
     * 解析失败仍回 200 OK(不让平台重试),但不触发 actions。
     */
    private fun handleSnapShotConfig(xml: String) {
        val cfg = com.uvp.sim.gb28181.SnapShotConfigParser.parse(xml) ?: return
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.SnapshotFlash,
                lastCommand = LastDeviceCommand("SnapShotConfig", cfg.sessionId, nowMs())
            )
        }
        scope?.launch { actions.triggerSnapshotConfig(cfg) }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}

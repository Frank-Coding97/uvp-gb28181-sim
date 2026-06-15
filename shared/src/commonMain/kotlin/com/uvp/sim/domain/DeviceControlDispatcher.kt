package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PtzCmdDecoder
import com.uvp.sim.gb28181.PtzCommand
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
    /** SnapShotCmd — 触发抓拍 + 上报(走已有 reportSnapshot 流程). */
    suspend fun snapshot()
    /** IFameCmd — 强制下一帧出 IDR. */
    fun requestKeyFrame()
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
            ManscdpParser.tagValue(xml, "IFameCmd") != null -> { handleIFrame(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "TeleBoot") != null -> { handleTeleBoot(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "RecordCmd") != null -> { handleRecord(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "GuardCmd") != null -> { handleGuard(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "AlarmCmd") != null -> handleAlarm(xml, fromUri)
            xml.contains("<DragZoomIn>") || xml.contains("<DragZoomOut>") -> { handleDragZoom(xml); DeviceControlAck() }
            xml.contains("<HomePosition>") -> { handleHomePosition(xml); DeviceControlAck() }
            xml.contains("<BasicParam>") -> { handleDeviceConfig(xml); DeviceControlAck() }
            ManscdpParser.tagValue(xml, "SnapShotCmd") != null -> { handleSnapshot(xml); DeviceControlAck() }
            else -> DeviceControlAck(needSipResponse = false)
        }
    }

    // ---------- PTZ ----------

    private fun handlePtz(xml: String) {
        val hex = ManscdpParser.tagValue(xml, "PTZCmd") ?: return
        val ptz = PtzCmdDecoder.decode(hex) ?: return
        state.update {
            it.copy(
                panSpeed = mapPanSpeed(ptz),
                tiltSpeed = mapTiltSpeed(ptz),
                zoomSpeed = mapZoomSpeed(ptz),
                lastCommand = LastDeviceCommand("PTZCmd", hex, nowMs(), ptz)
            )
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
        val enabled = ManscdpParser.tagValue(xml, "Enabled")
        if (enabled != null && enabled != "1") return
        val idx = ManscdpParser.tagValue(xml, "PresetIndex")?.toIntOrNull() ?: return
        val existing = state.value.presets[idx]
        if (existing == null) {
            val now = state.value
            val pose = PtzPose(now.panAngle, now.tiltAngle, now.zoomLevel)
            state.update {
                it.copy(
                    presets = it.presets + (idx to pose),
                    currentPresetIndex = idx,
                    lastCommand = LastDeviceCommand("HomePosition", "Set#$idx", nowMs())
                )
            }
        } else {
            state.update {
                it.copy(
                    currentPresetIndex = idx,
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

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}

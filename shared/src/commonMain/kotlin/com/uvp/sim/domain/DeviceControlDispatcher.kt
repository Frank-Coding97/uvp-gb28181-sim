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
 * 6. AlarmCmd      — 复位报警灯
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
     */
    fun dispatch(xml: String) {
        if (xml.isBlank()) return

        when {
            ManscdpParser.tagValue(xml, "PTZCmd") != null -> handlePtz(xml)
            ManscdpParser.tagValue(xml, "IFameCmd") != null -> handleIFrame(xml)
            ManscdpParser.tagValue(xml, "TeleBoot") != null -> handleTeleBoot(xml)
            ManscdpParser.tagValue(xml, "RecordCmd") != null -> handleRecord(xml)
            ManscdpParser.tagValue(xml, "GuardCmd") != null -> handleGuard(xml)
            ManscdpParser.tagValue(xml, "AlarmCmd") != null -> handleAlarm(xml)
            xml.contains("<DragZoomIn>") || xml.contains("<DragZoomOut>") -> handleDragZoom(xml)
            xml.contains("<HomePosition>") -> handleHomePosition(xml)
            xml.contains("<BasicParam>") -> handleDeviceConfig(xml)
            ManscdpParser.tagValue(xml, "SnapShotCmd") != null -> handleSnapshot(xml)
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

    private fun handleAlarm(xml: String) {
        val v = ManscdpParser.tagValue(xml, "AlarmCmd") ?: return
        state.update {
            it.copy(
                isAlarming = false,
                lastCommand = LastDeviceCommand("AlarmCmd", v, nowMs())
            )
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

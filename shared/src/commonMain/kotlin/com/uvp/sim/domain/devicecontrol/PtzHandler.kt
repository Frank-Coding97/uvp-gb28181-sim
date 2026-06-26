package com.uvp.sim.domain.devicecontrol

import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.DragZoomRect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.gb28181.CruiseOp
import com.uvp.sim.gb28181.FocusDirection
import com.uvp.sim.gb28181.IrisDirection
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PtzCmdDecoder
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.PtzInstruction
import com.uvp.sim.gb28181.PtzPreciseCtrlParser
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * PTZ 家族命令 handler:8 方向运动 + zoom + focus + iris + 巡航 + 精确云台 + DragZoom。
 * 预置位 / Aux 单独由 [PresetHandler] / [AuxHandler] 处理。
 */
interface PtzHandler {
    /** PTZCmd 8 字节解码 → 速率写入 state(motion + cruise 子族;preset/aux 走 presetHandler/auxHandler)。 */
    fun handlePtz(xml: String, presetHandler: PresetHandler, auxHandler: AuxHandler)

    /** PTZPreciseCtrl(GB-2022 A.2.3.1.11) — 精确云台目标位姿。 */
    fun handlePtzPrecise(xml: String)

    /** DragZoomIn / DragZoomOut — 平台拉框聚焦,记录矩形。 */
    fun handleDragZoom(xml: String)
}

internal class DefaultPtzHandler(
    private val state: MutableStateFlow<DeviceControlState>,
) : PtzHandler {

    override fun handlePtz(xml: String, presetHandler: PresetHandler, auxHandler: AuxHandler) {
        val hex = ManscdpParser.tagValue(xml, "PTZCmd") ?: return
        when (val ins = PtzCmdDecoder.decodeInstruction(hex)) {
            is PtzInstruction.Motion -> handlePtzMotion(ins.cmd, hex)
            is PtzInstruction.Preset -> presetHandler.handlePtzPreset(ins, hex)
            is PtzInstruction.Aux -> auxHandler.handlePtzAux(ins, hex)
            is PtzInstruction.Cruise -> handlePtzCruise(ins, hex)
            null -> { /* 校验失败 / 未知子族,200 OK 由 ack 兜底 */ }
        }
    }

    private fun handlePtzMotion(ptz: PtzCommand, hex: String) {
        state.update {
            // Focus 累计:WVP 按住按钮每秒发 ~5-10 条,每条 +/- 一个 step.
            // step = focusSpeed * 0.005,speed=15 → 0.075/cmd,speed=1 → 0.005/cmd.
            val focusDelta = when (ptz.focusDirection) {
                FocusDirection.NEAR -> -ptz.focusSpeed * 0.005f
                FocusDirection.FAR -> ptz.focusSpeed * 0.005f
                FocusDirection.NONE -> 0f
            }
            val newFocusLevel = (it.focusLevel + focusDelta).coerceIn(0f, 1f)
            // Iris 累计:跟 focus 同步累加机制
            val irisDelta = when (ptz.irisDirection) {
                IrisDirection.OPEN -> ptz.irisSpeed * 0.005f
                IrisDirection.CLOSE -> -ptz.irisSpeed * 0.005f
                IrisDirection.NONE -> 0f
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
            CruiseOp.SET_POINT -> {
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
            CruiseOp.DEL_POINT -> {
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
            CruiseOp.SET_SPEED -> {
                state.update { it.copy(lastCommand = LastDeviceCommand("PTZCmd", "巡航 #${p.trackNum} 速度=${p.param}", now)) }
            }
            CruiseOp.SET_DWELL_TIME -> {
                state.update { it.copy(lastCommand = LastDeviceCommand("PTZCmd", "巡航 #${p.trackNum} 停留=${p.param}s", now)) }
            }
            CruiseOp.START -> {
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

    override fun handlePtzPrecise(xml: String) {
        val p = PtzPreciseCtrlParser.parse(xml) ?: return
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

    override fun handleDragZoom(xml: String) {
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
}

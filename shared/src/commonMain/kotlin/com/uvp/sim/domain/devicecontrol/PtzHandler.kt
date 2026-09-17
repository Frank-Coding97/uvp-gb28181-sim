package com.uvp.sim.domain.devicecontrol

import com.uvp.sim.domain.DeviceControlModel
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
    private val state: MutableStateFlow<DeviceControlModel>,
) : PtzHandler {

    override fun handlePtz(xml: String, presetHandler: PresetHandler, auxHandler: AuxHandler) {
        val hex = ManscdpParser.tagValue(xml, "PTZCmd") ?: return
        when (val ins = PtzCmdDecoder.decodeInstruction(hex)) {
            is PtzInstruction.Motion -> handlePtzMotion(ins.cmd, hex)
            is PtzInstruction.Lens -> handlePtzLens(ins, hex)
            is PtzInstruction.Preset -> presetHandler.handlePtzPreset(ins, hex)
            is PtzInstruction.Aux -> auxHandler.handlePtzAux(ins, hex)
            is PtzInstruction.Cruise -> handlePtzCruise(ins, hex)
            // 校验失败 / 标准未定义该字节4(null)→ 不猜语义,200 OK 由 ack 兜底
            null -> Unit
        }
    }

    /**
     * PTZ 族(表 A.5):只动云台三轴。
     *
     * ⚠️ 这里**不再**累计聚焦/光圈。本族字节4 的 bit7/bit6 恒为 0;原实现却在这里按
     * bit7/bit6 判 Focus Near/Far、又把字节6 的低 4 位当聚焦速度,于是 0x44/0x48
     * (光圈放大/缩小)落进来被拆成「聚焦远 + 下/上」—— 现象就是**平台一调光圈、
     * 云台跟着仰俯**。聚焦/光圈已归 FI 族,见 [handlePtzLens]。
     */
    private fun handlePtzMotion(ptz: PtzCommand, hex: String) {
        state.update {
            it.copy(
                panSpeed = mapPanSpeed(ptz),
                tiltSpeed = mapTiltSpeed(ptz),
                zoomSpeed = mapZoomSpeed(ptz),
                lastCommand = LastDeviceCommand("PTZCmd", hex, nowMs(), ptz)
            )
        }
    }

    /**
     * FI 族(GB/T 28181-2022 表 A.6):聚焦 / 光圈。
     *
     * 写进去的是**速率**而不是位置 —— 标准里 FI 命令与方向命令同形:带速度的"开始动作"
     * 指令,字节4 低 4 位清零(0x40)才停。所以设备侧存速度、位置由 UI 的积分节拍
     * (见 `PtzTabContent`)按「速率 × 时间」推出来;松手时平台补一条 0x40 → 速率归零 → 停。
     *
     * 两轴可以同时有效(表下注 1 允许组合下发,如 0x46 = 光圈放大 + 聚焦近),各自独立推进。
     * 速度字节也是各占一个:字节5 = 聚焦速度、字节6 = 光圈速度(不对称是标准本身如此)。
     *
     * **不动云台三轴**:FI 命令不涉及 pan/tilt/zoom,那三轴该按自己的速率继续走,
     * 由各自的停止指令负责收尾 —— 在 FI 里顺手把它们清零会造出标准没定义的行为。
     */
    private fun handlePtzLens(lens: PtzInstruction.Lens, hex: String) {
        state.update {
            it.copy(
                focusSpeed = when (lens.focus) {
                    FocusDirection.NEAR -> -lens.focusSpeed * LENS_RATE_PER_SPEED
                    FocusDirection.FAR -> lens.focusSpeed * LENS_RATE_PER_SPEED
                    FocusDirection.NONE -> 0f
                },
                irisSpeed = when (lens.iris) {
                    IrisDirection.OPEN -> lens.irisSpeed * LENS_RATE_PER_SPEED
                    IrisDirection.CLOSE -> -lens.irisSpeed * LENS_RATE_PER_SPEED
                    IrisDirection.NONE -> 0f
                },
                lastCommand = LastDeviceCommand("PTZCmd", hex, nowMs(), lens.toPtzCommand()),
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

/**
 * FI 速度(标准口径 00H~FFH)→ 归一化行程速率(每秒)。
 *
 * `255 × (0.5/255) = 0.5/s`,即满速推完一整条行程(0%~100%)约 2 秒。
 * 平台默认档位 6 换算过来是 153,约 3.3 秒走完 —— 跟方向盘满速转一圈同一量级,
 * 演示时既看得出在动、又不会一按就到头。
 */
private const val LENS_RATE_PER_SPEED = 0.5f / 255f

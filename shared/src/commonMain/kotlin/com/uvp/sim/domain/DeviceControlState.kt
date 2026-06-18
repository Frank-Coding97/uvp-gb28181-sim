package com.uvp.sim.domain

import com.uvp.sim.gb28181.PtzCommand

/**
 * 设备控制运行时状态.
 *
 * 由 [DeviceControlDispatcher] 在收到平台 DeviceControl MESSAGE 后写入,
 * UI 3D 渲染层(SimulateScreen)通过 StateFlow 订阅消费.
 *
 * 设计:
 * - panAngle/tiltAngle/zoomLevel 是积累量,由 UI 层每帧基于 panSpeed 等积分得到,
 *   shared 层不主动驱动动画(否则需要协程 / Choreographer 跨平台抽象).
 * - panSpeed 为带方向有符号速率(负=左/下,正=右/上),零=停止.
 * - pendingEffect 是一次性事件,UI 消费后 emit 清零(setPendingEffect(null)).
 */
data class DeviceControlState(
    // PTZ 当前姿态(累积量,UI 层维护)
    val panAngle: Float = 0f,
    val tiltAngle: Float = 0f,
    val zoomLevel: Float = 1f,
    val irisLevel: Float = 0.5f,
    val focusLevel: Float = 0.5f,

    // PTZ 实时速率(由 PTZCmd 写入,UI 层每帧消费)
    val panSpeed: Float = 0f,
    val tiltSpeed: Float = 0f,
    val zoomSpeed: Float = 0f,

    // 状态灯
    val isRecording: Boolean = false,
    val isGuarded: Boolean = false,
    val isAlarming: Boolean = false,
    val isRebooting: Boolean = false,

    // DragZoom 区域(平台拉框聚焦)
    val dragZoomRect: DragZoomRect? = null,

    // 预置位 (HomePosition)
    val presets: Map<Int, PtzPose> = emptyMap(),
    val currentPresetIndex: Int? = null,

    // 最近一次平台控制命令(HUD 显示用)
    val lastCommand: LastDeviceCommand? = null,

    // GB-2022 §9.3.4 PTZPreciseCtrl 收到的最近一次精确控制指令,
    // 供 §9.5.3 PTZ 精准状态查询(A.2.4.13)回包用。null 表示从未收过。
    val lastPreciseCtrl: PtzPose? = null,

    // 一次性效果触发器,UI 消费后置 null
    val pendingEffect: DeviceEffect? = null,
)

data class PtzPose(val pan: Float, val tilt: Float, val zoom: Float)

data class DragZoomRect(
    val midX: Int,
    val midY: Int,
    val lengthX: Int,
    val lengthY: Int,
)

data class LastDeviceCommand(
    val type: String,
    val rawHex: String,
    val timestampMs: Long,
    val ptz: PtzCommand? = null,
)

sealed class DeviceEffect {
    data object IFrameFlash : DeviceEffect()
    data object Reboot : DeviceEffect()
    data object SnapshotFlash : DeviceEffect()
    data class HomePositionReturn(val targetPose: PtzPose) : DeviceEffect()
    /** 预置位调用 — 跟看守位 [HomePositionReturn] 区分,UI 同时高亮 chip */
    data class PresetRecall(val index: Int, val targetPose: PtzPose) : DeviceEffect()
    /** GB-2022 §9.3.4 PTZPreciseCtrl 触发的精确角度跳转 */
    data class PrecisePoseGoto(val targetPose: PtzPose) : DeviceEffect()
    data class ConfigChanged(val changedFields: List<String>) : DeviceEffect()
    /** GB-2022 §9.3.4 DeviceUpgrade — UI snackbar 提示,不真 OTA */
    data class DeviceUpgradeRequested(val firmware: String) : DeviceEffect()
    /** GB-2022 §9.3.4 FormatSDCard — UI snackbar 提示,不真格式化 */
    data class FormatSDCardRequested(val cardIndex: Int) : DeviceEffect()
}

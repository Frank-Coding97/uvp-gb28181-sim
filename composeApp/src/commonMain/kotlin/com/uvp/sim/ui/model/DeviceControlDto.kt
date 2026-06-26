package com.uvp.sim.ui.model

/**
 * UI 层 PTZ 姿态 DTO. 1:1 映射 com.uvp.sim.domain.PtzPose.
 * 用 data class 而不是 inline value class, UI Compose 状态需要 equals/hashCode 节流.
 */
data class PtzPoseDto(val pan: Float, val tilt: Float, val zoom: Float)

/** UI 层拖框变焦区域 DTO. 1:1 映射 com.uvp.sim.domain.DragZoomRect. */
data class DragZoomRectDto(
    val midX: Int,
    val midY: Int,
    val lengthX: Int,
    val lengthY: Int,
)

/** PTZ 命令方向(plan §1.3 #2 补). 1:1 映射 com.uvp.sim.gb28181.PtzCommand 5 个方向 enum. */
enum class PanDirectionDto { LEFT, RIGHT, NONE }
enum class TiltDirectionDto { UP, DOWN, NONE }
enum class ZoomDirectionDto { IN, OUT, NONE }
enum class FocusDirectionDto { NEAR, FAR, NONE }
enum class IrisDirectionDto { OPEN, CLOSE, NONE }

/** UI 层 PTZ 命令 DTO. 1:1 映射 com.uvp.sim.gb28181.PtzCommand. */
data class PtzCommandDto(
    val panDirection: PanDirectionDto,
    val tiltDirection: TiltDirectionDto,
    val zoomDirection: ZoomDirectionDto,
    val focusDirection: FocusDirectionDto = FocusDirectionDto.NONE,
    val irisDirection: IrisDirectionDto = IrisDirectionDto.NONE,
    val panSpeed: Int,
    val tiltSpeed: Int,
    val zoomSpeed: Int,
    val focusSpeed: Int = 0,
    val irisSpeed: Int = 0,
)

/** UI 层 设备命令记录 DTO. 1:1 映射 com.uvp.sim.domain.LastDeviceCommand. */
data class LastDeviceCommandDto(
    val type: String,
    val rawHex: String,
    val timestampMs: Long,
    val ptz: PtzCommandDto? = null,
)

/** 升级结果. 1:1 映射 com.uvp.sim.domain.UpgradeResult. */
enum class UpgradeResultDto { InProgress, Success, Failure }

/** UI 层 升级进度 DTO(plan §1.3 #1 补). 1:1 映射 com.uvp.sim.domain.UpgradeProgress. */
data class UpgradeProgressDto(
    val sessionId: String,
    val firmware: String,
    val percent: Int,
    val result: UpgradeResultDto,
)

/**
 * UI 层 设备控制状态 DTO. 1:1 映射 com.uvp.sim.domain.DeviceControlState.
 * 23 字段全部保留, pendingEffect 嵌套 DeviceEffectDto.
 */
data class DeviceControlDto(
    val panAngle: Float = 0f,
    val tiltAngle: Float = 0f,
    val zoomLevel: Float = 1f,
    val irisLevel: Float = 0.5f,
    val focusLevel: Float = 0.5f,
    val panSpeed: Float = 0f,
    val tiltSpeed: Float = 0f,
    val zoomSpeed: Float = 0f,
    val isRecording: Boolean = false,
    val isGuarded: Boolean = false,
    val isAlarming: Boolean = false,
    val isRebooting: Boolean = false,
    val dragZoomRect: DragZoomRectDto? = null,
    val presets: Map<Int, PtzPoseDto> = emptyMap(),
    val currentPresetIndex: Int? = null,
    val homePosition: PtzPoseDto? = null,
    val homePositionEnabled: Boolean = true,
    val cruiseTracks: Map<Int, List<Int>> = emptyMap(),
    val activeCruiseTrack: Int? = null,
    val auxStates: Map<Int, Boolean> = emptyMap(),
    val auxTimestamps: Map<Int, Long> = emptyMap(),
    val lastCommand: LastDeviceCommandDto? = null,
    val lastPreciseCtrl: PtzPoseDto? = null,
    val upgradeProgress: UpgradeProgressDto? = null,
    val pendingEffect: DeviceEffectDto? = null,
)

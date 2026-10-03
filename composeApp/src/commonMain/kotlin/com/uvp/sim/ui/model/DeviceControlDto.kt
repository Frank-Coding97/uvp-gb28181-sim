package com.uvp.sim.ui.model

/**
 * UI 层 PTZ 姿态 DTO. 1:1 映射 com.uvp.sim.domain.PtzPose.
 * 用 data class 而不是 inline value class, UI Compose 状态需要 equals/hashCode 节流.
 */
data class PtzPoseDto(val pan: Float, val tilt: Float, val zoom: Float)

/** UI 层拖框变焦区域 DTO. 1:1 映射 com.uvp.sim.domain.DragZoomRect. */
/**
 * 平台拉框（A.2.3.1.8/.9）的**原始像素**，含 [frameLength] / [frameWidth] 两把尺子
 * （标准 `Length` / `Width` = **播放窗口**的尺寸像素）。
 *
 * ⛔ 没有这两把尺子就算不出归一化坐标 —— 展示端画框、渲染端裁画面都依赖它们。
 */
data class DragZoomRectDto(
    val midX: Int,
    val midY: Int,
    val lengthX: Int,
    val lengthY: Int,
    val frameLength: Int = 0,
    val frameWidth: Int = 0,
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

/**
 * UI 层 平台控制命令的语义分类. 1:1 映射 com.uvp.sim.domain.DeviceCommandCategory.
 * **轨 ④ PR-UI-PROTOCOL-FIX**:UI 用它派 Tab,不再 parse rawHex.
 */
enum class DeviceCommandCategoryDto { Ptz, Status, Image, Aux }

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
 * UI 层 巡航轨迹 DTO. 1:1 映射 com.uvp.sim.domain.CruiseTrackState.
 *
 * `speed`/`dwellTime` 是**组级**的(控制层 `0x86`/`0x87` 只带巡航组号,不带点编号),
 * null = 平台从未下发过该项,查询应答这时回设备默认值。
 */
data class CruiseTrackDto(
    val points: List<Int> = emptyList(),
    val speed: Int? = null,
    val dwellTime: Int? = null,
)

/**
 * UI 层 扫描组 DTO. 1:1 映射 com.uvp.sim.domain.ScanGroupState.
 *
 * ⚠️ 两个边界是**设备当时那个点的完整姿态快照** —— 标准的设边界指令没有数值入参
 * (表 A.10 序号 2/3 的字节6 只是子动作码),设备只能记下自己此刻的朝向。
 * 但横扫只驱动水平轴(注4:画面自右向左移动),所以 HUD 只读它们的 `pan`。
 *
 * `null` = 平台没设过这一侧(不要回落成 0° —— 那会显示成一个"真边界")。
 */
data class ScanGroupDto(
    val leftBoundary: PtzPoseDto? = null,
    val rightBoundary: PtzPoseDto? = null,
    val speed: Int? = null,
)

/** UI 层 存储卡状态. 1:1 映射 com.uvp.sim.domain.StorageCardStatus(A.2.6.16 的五个小写取值)。 */
enum class StorageCardStatusDto { Ok, Formatting, Unformatted, Idle, Error }

/** UI 层 一张虚拟存储卡的物理属性. 1:1 映射 com.uvp.sim.domain.StorageCard. */
data class StorageCardDto(
    val id: Int,
    val name: String,
    val capacityMb: Int,
)

/**
 * UI 层 某张卡的一次读数. 1:1 映射 com.uvp.sim.domain.StorageCardReading.
 *
 * [progress] 只在 [status] 为 [StorageCardStatusDto.Formatting] 时有值 —— 其余状态为 null,
 * 不是 0。UI 也该照这个语义渲染("格式化中 45%" vs 别的状态不显示进度)。
 */
data class StorageCardReadingDto(
    val cardId: Int,
    val status: StorageCardStatusDto,
    val progress: Int?,
    val freeMb: Int,
)

/**
 * UI 层 设备控制状态 DTO. 1:1 映射 com.uvp.sim.domain.DeviceControlModel + 渲染派生字段.
 * 25 业务字段 + lastCommandCategory(渲染派生)— Mapper 由 (Model, RenderState) 双入参组装.
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
    /** FI 族(聚焦/光圈)实时速率,见 `DeviceControlModel.focusSpeed`。 */
    val focusSpeed: Float = 0f,
    val irisSpeed: Float = 0f,
    val isRecording: Boolean = false,
    val isGuarded: Boolean = false,
    val isAlarming: Boolean = false,
    val isRebooting: Boolean = false,
    val dragZoomRect: DragZoomRectDto? = null,
    val presets: Map<Int, PtzPoseDto> = emptyMap(),
    val currentPresetIndex: Int? = null,
    val homePosition: PtzPoseDto? = null,
    val homePositionEnabled: Boolean = true,
    /** 看守位指向的预置位号(`PresetIndex`)。null = 平台从未下发过看守位。 */
    val homePositionPresetIndex: Int? = null,
    /** 无云台操作后自动归位的等待秒数(`ResetTime`)。null = 平台未下发该项。 */
    val homePositionResetTime: Int? = null,
    val cruiseTracks: Map<Int, CruiseTrackDto> = emptyMap(),
    val activeCruiseTrack: Int? = null,
    /**
     * 平台设过边界的**扫描组**(GB/T 28181 表 A.10 自动扫描),key = 扫描组号。
     *
     * 空 = 平台从未设过。三个字段各自可空,语义与 `ScanGroupState` 一致 ——
     * 设备屏幕上要能说出"右边界还没设",而不是拿一个默认角度冒充。
     */
    val scanGroups: Map<Int, ScanGroupDto> = emptyMap(),
    /** 当前正在执行的扫描组号(null = 未扫描)。设备自主行为的运行态,不进存档。 */
    val activeScanGroup: Int? = null,
    val auxStates: Map<Int, Boolean> = emptyMap(),
    val auxTimestamps: Map<Int, Long> = emptyMap(),
    val lastCommand: LastDeviceCommandDto? = null,
    val lastPreciseCtrl: PtzPoseDto? = null,
    val upgradeProgress: UpgradeProgressDto? = null,
    val pendingEffect: DeviceEffectDto? = null,
    /**
     * 最近一次平台控制命令的语义分类(UI Tab 派发用),由 `deriveRenderState` 派生.
     * **轨 ④ PR-UI-PROTOCOL-FIX**:UI 视图读这个,不再 parse rawHex.
     */
    val lastCommandCategory: DeviceCommandCategoryDto? = null,

    // ---- GB-2022 A.2.4.14 / A.2.6.16 存储卡状态查询(模拟中心「存储卡」卡片)----
    /** 虚拟存储卡物理清单。**空 + [storageCardQueryCount]==0 才是「平台还没查过」**。 */
    val storageCards: List<StorageCardDto> = emptyList(),
    /** 最近一次查询读到的读数,按卡号索引。 */
    val storageCardReadings: Map<Int, StorageCardReadingDto> = emptyMap(),
    /** 最近一次收到查询的时刻(ms)。null = 从未查过。 */
    val storageCardQueriedAtMs: Long? = null,
    /** 累计查询次数 —— 卡片「亮起」的触发键(用计数而不是时间戳,见 domain 侧注释)。 */
    val storageCardQueryCount: Int = 0,

    // ---- GB-2022 A.2.3.2 设备配置族(画面遮挡 / 前端 OSD / 画面翻转 / 录像计划 / 报警录像 /
    //      报警上报 / 基本参数 / 图像抓拍)----
    /** 平台下发过的配置的只读视图态(画布叠层 + HUD 图像页摘要共用)。 */
    val deviceConfig: DeviceConfigDto = DeviceConfigDto(),

    // ---- GB-2022 A.2.3.1.14 目标跟踪 ----
    /**
     * 设备**当前**的跟踪态。`null` = 没在跟踪（含收到 `Stop` 之后）。
     *
     * ⛔ 这是目标跟踪在设备侧**唯一**的可见面：9.3.1 d) 把它列为无应答命令（表 1 序号 13
     * 应答栏"（无）"），平台收不到回执；附录 A 也没有任何查询命令能读回跟踪态。
     */
    val targetTrack: TargetTrackDto? = null,
)

/**
 * UI 层 目标跟踪态. 1:1 映射 com.uvp.sim.domain.TargetTrackState.
 *
 * ⛔ 没有 `Stop` 这一档：停止 = [DeviceControlDto.targetTrack] 为 `null`，
 * 不存在"正在停止的跟踪"。
 */
data class TargetTrackDto(
    val mode: TargetTrackModeDto,
    /** 设备手上**可画**的框（已归一化 0~1）。`null` = 报文没带框 / 框算不出来 / 整框在画面外。 */
    val box: TargetTrackBoxDto?,
    /** A.2.3.1.14 的 `<DeviceID2>`（全景通道 ID）。`null` = 平台没带这个元素。 */
    val deviceId2: String?,
    val startedAtMs: Long,
)

/** UI 层 跟踪模式. 1:1 映射 com.uvp.sim.domain.TargetTrackMode. */
enum class TargetTrackModeDto { Auto, Manual }

/** UI 层 归一化跟踪框(0~1,原点 = 画面左上角). 1:1 映射 com.uvp.sim.domain.TargetTrackBox. */
data class TargetTrackBoxDto(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

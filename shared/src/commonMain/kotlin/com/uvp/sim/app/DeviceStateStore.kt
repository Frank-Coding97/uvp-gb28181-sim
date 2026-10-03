package com.uvp.sim.app

import com.uvp.sim.domain.CruiseTrackState
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.ScanGroupState
import com.uvp.sim.gb28181.DeviceConfigState
import com.uvp.sim.gb28181.VideoParamState
import kotlinx.serialization.Serializable

/**
 * 设备侧运行状态持久化契约(2026-09-17)。
 *
 * **为什么需要它**:模拟器把 [DeviceControlModel] 整个放在内存里 —— `ConfigStore` 只持久化
 * [com.uvp.sim.config.SimConfig](SIP 配置)。于是 App 进程一重启,设备侧的**预置位**和
 * **巡航轨迹**全没了,而平台界面上完全看不出来:轨迹还躺在库里、`enabled` 是 NULL、
 * 点「开始巡航」照样下发成功、操作记录也是 `sent`,模拟器画面却纹丝不动 ——
 * 因为 `0x88 开始巡航` 引用的那些预置位在**设备侧**一个都不存在。
 * 现场排障时这表现为「巡航功能没做」,实际只是设备的数据没了。看守位同理
 * (它硬依赖预置位:设备侧没有那个号就完全不动作)。
 *
 * **为什么不并进 [ConfigStore]**:
 *   - 生命周期不同。配置是用户手填的、改一次算一次;设备状态是**运行期被平台下发改写**的,
 *     且姿态每 ~166ms 就可能变一次,需要独立的节流策略,不能拖累配置的写路径。
 *   - 失败代价不同。配置写失败得让用户看见;状态写失败掉一档演示精度即可,不该弹报错。
 *   - 存储介质不同。Android 配置走加密 DataStore(含 device.password),状态是可公开的演示数据。
 */
interface DeviceStateStore {
    /** 读存档。无存档 / 解析失败一律返回 null(= 按全新设备启动),不抛。 */
    suspend fun loadOnce(): DeviceStateSnapshot?

    /** 覆盖写存档。失败向上抛,由 [DeviceStatePersister] 决定重试与日志抑制。 */
    suspend fun save(snapshot: DeviceStateSnapshot)
}

/** 存档 schema 版本。字段语义变更时 +1(旧存档靠默认值降级,不写迁移代码)。 */
internal const val DEVICE_STATE_SCHEMA_VERSION = 1

/**
 * 设备侧运行状态存档 —— **只含"跨进程重启应当保留"的字段**。
 *
 * 为什么不直接序列化 [DeviceControlModel]:那是**运行时模型**,近一半字段的语义是
 * "这次会话里发生了什么"。一起存下来,冷启动后会恢复出**假运行时状态** —— HUD 显示一条
 * 并不存在的命令、看守位空闲倒计时拿一个陈旧时间戳起算、镜头凭一个不存在的力矩自己飘。
 *
 * 所以这里做一层显式存档 DTO:**字段清单就是产品决策清单**,加字段必须先回答
 * "重启后它该是什么"。顺带让 JSON schema 与 domain 字段解耦 —— domain 改字段名不会
 * 静默毁掉旧存档(反序列化走默认值),`schemaVersion` 只在该改语义时才动。
 *
 * **刻意不存的字段**(不存的理由和存的同样重要):
 *   - `panSpeed` / `tiltSpeed` / `zoomSpeed` / `focusSpeed` / `irisSpeed`:语义是"此刻正在转"。
 *     冷启动后没有任何命令在驱动,恢复出非零速率会让镜头自己飘起来。
 *     ⚠️ 扫描启动期间扫描节拍正是靠 `panSpeed` 驱动横扫 —— 这一条因此更硬:
 *     存了它,冷启动会得到一台**自己转个不停**的设备(没有任何停止指令能停它,因为
 *     平台上"当前在扫描"这件事根本不存在)。
 *   - `lastCommand`:那是"本次会话最近收到的一条命令"。恢复后 HUD 会显示一条**并不存在**的命令,
 *     更要命的是它同时是看守位的**活动信号**,拿陈旧时间戳起算会让空闲倒计时直接从中间开始。
 *   - `pendingEffect`:一次性动画触发器。存档它 = 每次冷启动重播一次闪白 / 重启动画。
 *   - `activeCruiseTrack`:巡航是"平台下发了启动**且设备正在跑节拍**"的会话内状态。进程重启后
 *     没有东西在驱动节拍,恢复它只能得到一个永远停在第一点的假巡航 —— 不如不巡,让操作员
 *     重新点一次(这也更接近真机行为:设备断电重启后不会自己接着巡)。
 *   - `activeScanGroup`:同 `activeCruiseTrack` 一条理由(扫描节拍同样不在重启后继续跑)。
 *     ⚠️ 但**边界与速度要存**(见 [scanGroups]) —— 那是设备侧的**配置**,
 *     真机断电重启后边界仍在;不存的话平台那边看起来就是"边界白设了",
 *     而标准里**没有任何查询命令**能把它们读回来对账(附录 A 无 `ScanQuery`)。
 *   - `isRecording` / `isAlarming` / `isRebooting` / `upgradeProgress` / `dragZoomRect` /
 *     `lastPreciseCtrl`:全是瞬时运行态。
 *   - `targetTrack`:目标跟踪态(GB-2022 A.2.3.1.14)。**不存的理由比 `activeScanGroup` 更硬**:
 *     那两条虽然"重启后没有节拍在驱动",但平台至少还能重新下发一次来纠正;而目标跟踪是
 *     **无应答命令**(9.3.1 d),平台既收不到回执、附录 A 又**没有**任何查询命令能读回跟踪态
 *     ⇒ 一旦恢复出一份**陈旧**的跟踪态,平台**没有任何手段**能发现设备上写着的是上一次会话的事。
 *     真机侧同理:断电重启后跟踪算法进程重新起来,不会接着盯上一条指令的目标。
 *     另:跟踪态里的 `startedAtMs` 和 `auxTimestamps` 是同一类"必须与本次进程同基准"的时间戳,
 *     跨重启恢复会让屏幕显示"已跟踪 3 小时"而实际刚开机。
 *   - `auxTimestamps`:UI 用它显示"本开关已运行多久"。它必须跟**本次进程**同基准,跨重启恢复
 *     会让用户看到"雨刷已运行 3 小时"而实际刚开机。`auxStates`(开/关本身)则是设备状态,存。
 */
@Serializable
data class DeviceStateSnapshot(
    val schemaVersion: Int = DEVICE_STATE_SCHEMA_VERSION,

    // ---- 镜头当前姿态(设备物理量:重启后镜头就停在那儿) ----
    val panAngle: Float = 0f,
    val tiltAngle: Float = 0f,
    val zoomLevel: Float = 1f,
    val irisLevel: Float = 0.5f,
    val focusLevel: Float = 0.5f,

    // ---- 预置位 ----
    val presets: Map<Int, PoseSnapshot> = emptyMap(),
    val currentPresetIndex: Int? = null,

    // ---- 看守位:配置三件套,坐标本体在 [presets] 里 ----
    val homePosition: PoseSnapshot? = null,
    val homePositionEnabled: Boolean = true,
    val homePositionPresetIndex: Int? = null,
    val homePositionResetTime: Int? = null,

    // ---- 巡航轨迹 ----
    val cruiseTracks: Map<Int, CruiseTrackSnapshot> = emptyMap(),

    /**
     * 平台设过的**扫描组**(GB/T 28181 表 A.10 自动扫描),key = 扫描组号。
     *
     * ⭐ **必须存**:左右边界与速度是设备侧的配置(真机断电重启后当然还在)。
     * 不存的后果比巡航那次更难看 —— 巡航丢的是点位链(平台还能重新下发),
     * 而扫描的边界**只能由平台"把当前位置设为边界"这一条指令写入**、**没有任何查询命令能回读**
     * (附录 A 里没有 `ScanQuery`)。设备侧一丢,平台那边没有任何办法发现丢的是哪一段:
     * 点「开始扫描」照样下发成功、设备也回 200 OK,但设备只会回一句"缺左右边界"——
     * 现场看到的却是"扫描功能不好使"。
     */
    val scanGroups: Map<Int, ScanGroupSnapshot> = emptyMap(),

    /**
     * 平台写入过的视频参数(GB-2022 A.2.1.13 `VideoParamAttribute`),key = 码流号。
     *
     * ⭐ **必须存**。这是"设备当前的编码配置",真机断电重启后当然还在。
     * 不存的后果不是"少显示几个数",而是:App 重启 → 回读退回**出厂默认** →
     * 平台面板上的值**自己变回去了**,而操作记录里那次下发明明是 `accepted` ——
     * 现场只会说「平台配完不管用」。
     *
     * (注意:即使这一项为空,回读也**不会**是空的 —— 设备会回出厂默认,
     *  所以不存在"重启把设备变成不支持该类型"的假阴性。这一项管的是值对不对。)
     */
    val videoParams: Map<Int, VideoParamSnapshot> = emptyMap(),

    /**
     * 平台写入过的**其余设备配置族**（GB/T 28181-2022 A.2.3.2：画面遮挡 / 前端 OSD /
     * 画面翻转 / 报警上报 / 录像计划 / 报警录像 / 基本参数 / 抓拍）。
     *
     * ⭐ **必须存**，与 [videoParams] 同一条理由：这是"设备当前的配置"，真机断电重启后当然还在。
     * 不存的后果不是"少显示几个数"，而是 App 重启 → 回读退回**出厂默认** →
     * 平台面板上的值自己变回去了（尤其遮挡：画布上的黑块会在重启后**消失**，
     * 而平台面板还显示着 2 个区域）。
     */
    val deviceConfigs: DeviceConfigState =
        DeviceConfigState(),

    // ---- 辅助开关(标准只定义编号 1 = 雨刷):设备状态,重启后仍应在原状态 ----
    val auxStates: Map<Int, Boolean> = emptyMap(),

    // ---- 状态灯里唯一"配置性"的一项:布防 ----
    val isGuarded: Boolean = false,
)

/**
 * 姿态存档。与 domain [PtzPose] 解耦成独立类型 —— 存档是**对外契约**,domain 重构不该改它,
 * 而且 [PtzPose] 加字段时不会静默改变已落盘 JSON 的形状。
 */
@Serializable
data class PoseSnapshot(val pan: Float, val tilt: Float, val zoom: Float)

/** 巡航轨迹存档(与 domain [CruiseTrackState] 1:1)。 */
@Serializable
data class CruiseTrackSnapshot(
    val points: List<Int> = emptyList(),
    val speed: Int? = null,
    val dwellTime: Int? = null,
)

/**
 * 扫描组存档(与 domain [ScanGroupState] 1:1)。
 *
 * 三个字段都可空,且 `null` 的语义必须原样保留 = "平台从未下发过这一项":
 * 恢复后设备屏幕上要能说出"右边界还没设",而不是拿 0° 冒充一个真边界。
 */
@Serializable
data class ScanGroupSnapshot(
    val leftBoundary: PoseSnapshot? = null,
    val rightBoundary: PoseSnapshot? = null,
    val speed: Int? = null,
)

/** 视频参数存档(与 [VideoParamState] 1:1)。取值全是字符串,存档不做任何归一。 */
@Serializable
data class VideoParamSnapshot(
    val streamNumber: Int,
    val videoFormat: String = "",
    val resolution: String = "",
    val frameRate: String = "",
    val bitRateType: String = "",
    /** 条件必选:仅 CBR 时非 null。存档里 `null` 与 `""` 的区分同样要保留。 */
    val videoBitRate: String? = null,
)

/** 是否"有内容"。全新安装落盘的默认快照与之恒等,用来区分"真恢复"和"什么也没有"。 */
internal val DeviceStateSnapshot.hasContent: Boolean
    get() = presets.isNotEmpty() || cruiseTracks.isNotEmpty() || auxStates.isNotEmpty() ||
        videoParams.isNotEmpty() || deviceConfigs != DeviceConfigState() ||
        scanGroups.isNotEmpty() ||
        homePositionPresetIndex != null || homePosition != null || currentPresetIndex != null ||
        isGuarded || panAngle != 0f || tiltAngle != 0f || zoomLevel != 1f ||
        irisLevel != 0.5f || focusLevel != 0.5f

/** 存档摘要 —— 恢复时打一条日志,现场一眼看出"设备侧重启后到底有没有数据"。 */
internal fun DeviceStateSnapshot.summary(): String = buildString {
    append("预置位 ${presets.size} 个")
    append(" / 巡航轨迹 ${cruiseTracks.size} 条")
    // 扫描组只报"设过几个组";边界值是真值,不在这里展开(日志要能一眼读完)。
    // ⚠️ 这条存在的意义:扫描边界**没有任何查询命令能回读**,平台永远看不到设备侧到底存了什么,
    //    所以"设备重启后边界还在不在"只能靠这条日志对。
    if (scanGroups.isNotEmpty()) append(" / 扫描组 ${scanGroups.size} 个(平台设过边界)")
    append(" / 辅助开关 ${auxStates.size} 个")
    if (videoParams.isNotEmpty()) append(" / 视频参数 ${videoParams.size} 路(平台配过)")
    // 设备配置族:只列"平台真的下发过"的那几类(用 configuredBlocks 同一份判断,
    // 免得日志与实际回读口径分家)。没有就不占位 —— 免得每次启动都拖着一段空说明。
    val configuredBlocks = deviceConfigs.configuredBlocks
    if (configuredBlocks.isNotEmpty()) {
        append(" / 设备配置 ${configuredBlocks.size} 类(平台配过:" +
            configuredBlocks.joinToString(",") { it.configType } + ")")
    }
    if (homePositionPresetIndex != null) append(" / 看守位 → P${homePositionPresetIndex}")
    append(" / 姿态 pan=${panAngle} tilt=${tiltAngle} zoom=${zoomLevel}")
}

/** Model → 存档。字段取舍理由见 [DeviceStateSnapshot] 的注释。 */
internal fun DeviceControlModel.toDeviceStateSnapshot(): DeviceStateSnapshot =
    DeviceStateSnapshot(
        panAngle = panAngle,
        tiltAngle = tiltAngle,
        zoomLevel = zoomLevel,
        irisLevel = irisLevel,
        focusLevel = focusLevel,
        presets = presets.mapValues { it.value.toSnapshot() },
        currentPresetIndex = currentPresetIndex,
        homePosition = homePosition?.toSnapshot(),
        homePositionEnabled = homePositionEnabled,
        homePositionPresetIndex = homePositionPresetIndex,
        homePositionResetTime = homePositionResetTime,
        cruiseTracks = cruiseTracks.mapValues { it.value.toSnapshot() },
        scanGroups = scanGroups.mapValues { it.value.toSnapshot() },
        videoParams = videoParams.mapValues { it.value.toSnapshot() },
        deviceConfigs = deviceConfigs,
        auxStates = auxStates,
        isGuarded = isGuarded,
    )

/**
 * 存档 → Model。**只覆盖自己管的那几个字段**,其余原样保留 —— 恢复是"补一段历史",
 * 不是"把 Model 换掉"。`pendingEffect` / `lastCommand` 等运行时字段因此天然不受影响。
 */
internal fun DeviceStateSnapshot.restoreInto(model: DeviceControlModel): DeviceControlModel =
    model.copy(
        panAngle = panAngle,
        tiltAngle = tiltAngle,
        zoomLevel = zoomLevel,
        irisLevel = irisLevel,
        focusLevel = focusLevel,
        presets = presets.mapValues { it.value.toPose() },
        currentPresetIndex = currentPresetIndex,
        homePosition = homePosition?.toPose(),
        homePositionEnabled = homePositionEnabled,
        homePositionPresetIndex = homePositionPresetIndex,
        homePositionResetTime = homePositionResetTime,
        cruiseTracks = cruiseTracks.mapValues { it.value.toState() },
        // ⚠️ 刻意不动 `activeScanGroup`:存档里根本没有它(见 [DeviceStateSnapshot] 的说明),
        //    `copy` 不写它 = 保持 Model 现值 = 全新设备启动时是 null。
        scanGroups = scanGroups.mapValues { it.value.toState() },
        videoParams = videoParams.mapValues { it.value.toState() },
        deviceConfigs = deviceConfigs,
        auxStates = auxStates,
        isGuarded = isGuarded,
    )

internal fun PtzPose.toSnapshot(): PoseSnapshot = PoseSnapshot(pan, tilt, zoom)

internal fun PoseSnapshot.toPose(): PtzPose = PtzPose(pan, tilt, zoom)

internal fun CruiseTrackState.toSnapshot(): CruiseTrackSnapshot =
    CruiseTrackSnapshot(points = points, speed = speed, dwellTime = dwellTime)

internal fun CruiseTrackSnapshot.toState(): CruiseTrackState =
    CruiseTrackState(points = points, speed = speed, dwellTime = dwellTime)

internal fun ScanGroupState.toSnapshot(): ScanGroupSnapshot =
    ScanGroupSnapshot(
        leftBoundary = leftBoundary?.toSnapshot(),
        rightBoundary = rightBoundary?.toSnapshot(),
        speed = speed,
    )

internal fun ScanGroupSnapshot.toState(): ScanGroupState =
    ScanGroupState(
        leftBoundary = leftBoundary?.toPose(),
        rightBoundary = rightBoundary?.toPose(),
        speed = speed,
    )

internal fun VideoParamState.toSnapshot(): VideoParamSnapshot =
    VideoParamSnapshot(
        streamNumber = streamNumber,
        videoFormat = videoFormat,
        resolution = resolution,
        frameRate = frameRate,
        bitRateType = bitRateType,
        videoBitRate = videoBitRate,
    )

internal fun VideoParamSnapshot.toState(): VideoParamState =
    VideoParamState(
        streamNumber = streamNumber,
        videoFormat = videoFormat,
        resolution = resolution,
        frameRate = frameRate,
        bitRateType = bitRateType,
        videoBitRate = videoBitRate,
    )

package com.uvp.sim.domain

import com.uvp.sim.gb28181.DeviceConfigState
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.VideoParamState
import com.uvp.sim.osd.VideoDragZoomViewport

/**
 * Wave 3 PR-DC-DECOUPLE(2026-06-26):「业务 Model」+ 「UI RenderState」两层架构。
 *
 * **DeviceControlModel** 承载所有「业务 / 协议层」运行状态:
 *  - PTZ 姿态(panAngle/tiltAngle/zoomLevel) — preset SET 读、PreciseCtrl Query 回包用
 *  - PTZ 速率 + iris/focus 累计量 — Handler 写,UI 每帧消费
 *  - 状态灯(isRecording/isGuarded/isAlarming/isRebooting) — Query 回包用
 *  - preset CRUD / homePosition / cruiseTracks / auxStates / lastPreciseCtrl / upgradeProgress —
 *    各 §9.5.3 Query 回包用
 *  - lastCommand / pendingEffect — Handler 写,Coord 读出转 SimEvent / UI 消费一次性效果
 *
 * **DeviceControlRenderState**(独立文件)承载「纯渲染派生」字段(rawHex 文本 / 时间戳显示 / icon 提示等),
 * 由 [deriveRenderState] 从 Model 纯函数推导,**不独立 hold 状态**。
 *
 * 拆分原则(测试驱动反推):凡是 Handler / Dispatcher / Coord / 业务单测**写或读**的字段进 Model;
 * 仅 UI Mapper / Compose 视图**消费**且可由 Model 推导的字段进 RenderState。
 *
 * Handler / Dispatcher / Coord 全部用 `MutableStateFlow<DeviceControlModel>`;
 * UI 层(AppEngine.deviceControlState / SimulatorEngine.deviceControlState)直接暴露
 * [DeviceControlModel] StateFlow,UI Mapper 在边界处调 [deriveRenderState] 派生
 * 渲染层语义字段(含 [DeviceCommandCategory]),协议字符串解析压在派生函数里,不漏到 UI。
 */
data class DeviceControlModel(
    // PTZ 当前姿态(累积量,UI 层维护)
    val panAngle: Float = 0f,
    val tiltAngle: Float = 0f,
    val zoomLevel: Float = 1f,
    /** 光圈位置 0~1(归一化行程)。位置由 [irisSpeed] 积分而来,不是每条命令加一点。 */
    val irisLevel: Float = 0.5f,
    /** 聚焦位置 0~1(归一化行程)。同 [irisLevel]。 */
    val focusLevel: Float = 0.5f,

    // PTZ 实时速率(由 PTZCmd 写入,UI 层每帧消费)
    val panSpeed: Float = 0f,
    val tiltSpeed: Float = 0f,
    val zoomSpeed: Float = 0f,

    // FI 族(聚焦/光圈)实时速率 —— 与上面三轴同级,因为标准里 FI 命令的形态完全一样:
    // **带速度的"开始动作"指令**,而不是"走一步"指令(见 gb28181 表 A.6,字节5/6 是速度,
    // 字节4 低 4 位清零即停)。所以设备侧存的是速率,位置由速率积分而来。
    // 聚焦速度走字节5、光圈速度走字节6,单位都是 0~255。
    val focusSpeed: Float = 0f,
    val irisSpeed: Float = 0f,

    // 状态灯
    val isRecording: Boolean = false,
    val isGuarded: Boolean = false,
    val isAlarming: Boolean = false,
    val isRebooting: Boolean = false,

    // DragZoom 区域(平台拉框聚焦)
    val dragZoomRect: DragZoomRect? = null,

    /**
     * 设备**当前视窗**（拉框放大/缩小的累积结果，A.2.3.1.8/.9）。
     *
     * ⛔ 必须是**累积**的当前值，不是"最近一次的框"：标准把坐标系定义在**播放窗口**上，
     * 而播放窗口显示的是**当前**画面 —— 第二次拉框放大是"在已放大的画面上接着裁"。
     * 详见 [com.uvp.sim.osd.VideoDragZoomViewport]。
     *
     * ⭐ 这里是**真流**的输入：渲染端（Android `CameraTexturePass` / iOS `IosFrameProcessor`）
     * 逐帧读它来裁相机画面。⛔ 别再加第三个消费端（尤其别加回「模拟中心」画布）——
     * 画布与推流是两条互不相干的链路，画布上放大**不代表**平台点播到的画面会放大
     * （同 `FrameMirror` 那次的结论，见 `MonitoringStage` 文件头）。
     */
    val dragZoomViewport: VideoDragZoomViewport = VideoDragZoomViewport.IDENTITY,

    // 预置位 (HomePosition)
    val presets: Map<Int, PtzPose> = emptyMap(),

    /**
     * A.2.3.1.2 `PTZCmdParams/PresetName`（**2022 新增**）—— 平台在**设置**预置位时给的名字。
     *
     * 与 [presets] 分开存：那边是"位姿"（设备自己算出来的），这边是"平台起的名字"。
     * 合成一个类型要动 UI 与 PTZ 回调两处消费点，而名字只被 `PresetQuery` 回读用到。
     *
     * ⛔ 平台**没下发过**名字的预置位不进这个表 —— 回读时按 `Preset $idx` 兜底，
     * 而不是在这里预填一个假名（那会让"平台配过名"和"没配过"在设备侧再也分不出来）。
     */
    val presetNames: Map<Int, String> = emptyMap(),
    val currentPresetIndex: Int? = null,

    // 看守位(GB/T 28181-2016 §9.3.1 控制 / 2022 才补 §9.5.3 查询)—
    // 设备端存的是**配置三件套**,不是坐标:指向哪个预置位 + 无云台操作多久自动归位 + 开关。
    // 坐标本体在 [presets] 里,这里只存引用。跟预置位是不同概念。
    val homePosition: PtzPose? = null,          // 指向的预置位坐标快照(UI 回显用)
    val homePositionEnabled: Boolean = true,
    /** 看守位指向的预置位号(wire 上是 `PresetIndex`,0~255)。null = 平台从未下发过看守位。 */
    val homePositionPresetIndex: Int? = null,
    /** 无云台操作后自动归位的等待秒数(wire 上是 `ResetTime`)。null = 平台未下发该项。 */
    val homePositionResetTime: Int? = null,

    // GB-2022 §9.5.3 巡航轨迹 — 一组预置位序列 + 停留时长 + 速度
    // key = 轨迹号(控制层「巡航组号」= 查询层 <Number>),value = 该轨迹的配置
    val cruiseTracks: Map<Int, CruiseTrackState> = emptyMap(),
    /** 当前正在执行的巡航轨迹号(null 表示未巡航) */
    val activeCruiseTrack: Int? = null,

    /**
     * GB/T 28181 表 A.10 自动扫描 —— 每个扫描组号的**边界与速度**。
     * key = 扫描组号(wire 上字节5,`00H~FFH`,标准明确写了是"扫描组号"⇒ 支持多个组)。
     *
     * ⛔ 只在设备侧存,**没有任何查询命令能回读它**:附录 A 的查询族里没有 `ScanQuery`
     * (只有表 A.10 的控制指令),所以平台永远读不回边界值。别在这里为"回读"留钩子。
     */
    val scanGroups: Map<Int, ScanGroupState> = emptyMap(),

    /**
     * 当前正在执行的扫描组号(null = 未扫描)。
     *
     * ⚠️ 与 [activeCruiseTrack] 一样**不进存档**:扫描是"平台下发启动**且设备在跑节拍**"
     * 的会话内状态,冷启动后没有节拍在驱动,恢复它只能得到一条停在原地却标着"扫描中"的假运行态。
     */
    val activeScanGroup: Int? = null,

    /**
     * GB/T 28181-2022 A.2.3.1.14 **目标跟踪** —— 设备**当前**的跟踪态。`null` = 没在跟踪。
     *
     * ⛔ **必需**，不是"锦上添花的显示字段"：9.3.1 d) 把目标跟踪列为**无应答命令**
     * （表 1 序号 13 的应答栏是"（无）"），平台收不到任何回执；附录 A 也**没有**任何查询命令
     * 能把跟踪态读回去 ⇒ "平台点过什么"在设备屏幕上是唯一可见面。只写 [lastCommand]
     * 等于把这条命令的痕迹绑在一个 3 秒就过期的时间戳上。
     *
     * `Stop` 的语义是**置 null**：没有"停止中的跟踪态"这种东西（同 [activeCruiseTrack] /
     * [activeScanGroup] 的"运行态为空即停止"口径）。
     *
     * ⚠️ **不进存档**：真机断电重启后跟踪算法进程重新起来，不会接着盯上一条指令的目标；
     * 更要紧的是**平台没有任何手段能发现自己看到的是重启前的旧状态**（无回执、无查询）。
     * 见 `DeviceStateSnapshot` 的"刻意不存的字段"。
     */
    val targetTrack: TargetTrackState? = null,

    // 辅助开关状态(GB/T 28181 A.3.7 表 A.11,byte4=0x8C 开 / 0x8D 关,byte5=编号)
    // key = AuxFunction.index —— **标准只定义了 1 = 雨刷**,其余编号没有语义,不会出现在这里
    // value = true=ON / false=OFF
    val auxStates: Map<Int, Boolean> = emptyMap(),
    /** 辅助开关最近一次状态变更的时间戳(ms),供 UI 显示运行时长. */
    val auxTimestamps: Map<Int, Long> = emptyMap(),

    // 最近一次平台控制命令(Coord 读它转 SimEvent / UI HUD 显示)
    val lastCommand: LastDeviceCommand? = null,

    // GB-2022 §9.3.4 PTZPreciseCtrl 收到的最近一次精确控制指令,
    // 供 §9.5.3 PTZ 精准状态查询(A.2.4.13)回包用。null 表示从未收过。
    val lastPreciseCtrl: PtzPose? = null,

    // GB-2022 §9.13 设备升级进度(in-progress/success/failure + percent),
    // null 表示当前没有升级任务。
    val upgradeProgress: UpgradeProgress? = null,

    // GB-2022 A.2.4.14 / A.2.6.16 存储卡状态查询 —— 设备侧虚拟存储卡。
    // 4 个字段同进同出,唯一写入口是 [withStorageCardQuery](别在别处单独 copy 其中一个)。
    /**
     * 虚拟存储卡**物理属性**清单(张数/盘名/容量)。
     *
     * 空 = 平台从未查询过 —— 清单与读数一起在应答时写入,因为它们来自**同一次读盘**。
     * (另一种做法是装配期就把清单塞进来,让卡片在平台查询前也能显示容量;那会让本字段有
     *  两个写者,而"平台查之前设备屏幕上凭空有张卡"本身也不比"查了才出现"更真实。)
     */
    val storageCards: List<StorageCard> = emptyList(),
    /** 最近一次平台查询读到的读数,按卡号索引。空 = 平台从未查过(或设备确实没插卡,见下)。 */
    val storageCardReadings: Map<Int, StorageCardReading> = emptyMap(),
    /**
     * 最近一次收到存储卡查询的时间戳(ms)。null = 平台**从未查过**。
     *
     * ⛔ 必须与 [storageCardReadings] 为空区分开:「没查过」和「查了但没有卡」在 UI 上
     * 是两种完全不同的文案,只看 readings .isEmpty() 会把后者显示成"平台还没查"。
     */
    val storageCardQueriedAtMs: Long? = null,
    /**
     * 收到存储卡查询的**累计次数**。UI 卡片「亮起」的触发键。
     *
     * ⛔ 用计数而不是 [storageCardQueriedAtMs]:连点两次查询必须各亮一次,而同一毫秒内的
     * 两次查询时间戳相同 → Compose 的 `LaunchedEffect(时间戳)` 键不变,动效不重播,
     * 现象是"第二次点查询设备屏幕没反应"。
     */
    val storageCardQueryCount: Int = 0,

    // GB-2022 A.2.1.13 `VideoParamAttribute` —— 平台**写入**过的视频参数,key = StreamNumber。
    //
    // ⛔ 空 map ≠ 「设备不支持该配置类型」。回读时缺席的码流会退回**出厂默认**
    // (见 [com.uvp.sim.gb28181.VideoParamAttribute.effectiveParams]),所以回读应答
    // **永远有值** —— 真实设备一上电就有编码配置。把"没人配过"报成"不支持",
    // 是 A-5 判据链里最忌讳的假阴性。
    val videoParams: Map<Int, VideoParamState> = emptyMap(),

    /**
     * GB/T 28181-2022 A.2.3.2 设备配置族中**其余类型**的平台写入值
     * （画面遮挡 / 前端 OSD / 画面翻转 / 报警上报 / 录像计划 / 报警录像 / 基本参数 / 抓拍）。
     *
     * ⛔ 与 [videoParams] 并列而不是合并：那一个是按 `StreamNumber` 索引的逐码流表，
     * 语义与本聚合不同，且早已进存档、接 UI（见 [DeviceConfigState] 的类注释）。
     * 新类型**进本聚合**。
     *
     * 子项为 `null` = 平台从未下发过该类型 ⇒ 回读退回出厂默认（抓拍配置例外）。
     */
    val deviceConfigs: DeviceConfigState =
        DeviceConfigState(),

    // 一次性效果触发器,UI 消费后置 null
    val pendingEffect: DeviceEffect? = null,
)

/**
 * 记录一次存储卡状态查询的结果 —— **唯一的写入口**。
 *
 * ⛔ 为什么必须收口到一个函数:这 4 个字段是「一次查询」这一个事实的四个面。分两处 `update`
 * 写会让 Compose 读到「次数已经 +1、读数还是上一次」的中间态 —— 卡片先亮一下旧数据再跳,
 * 在真机上就是一次肉眼可见的闪动。同一次 `copy` 保证是单次 StateFlow 发射。
 *
 * ⛔ 刻意**不写** `lastCommand`:那是「云台活动信号」——`SimulatorEngine` 用它算看守位空闲
 * 倒计时(`CameraActivity(model.lastCommand?.timestampMs, …)`)。存储卡查询不是云台操作,
 * 写进去会把倒计时清零,表现为「平台在查存储卡,看守位就不自动归位了」。见
 * `HomePositionAutoReturn` 的注释。
 *
 * @param cards 设备侧虚拟存储卡物理清单([VirtualStorageCards.cards])
 * @param readings 本次读到的读数(每卡一条;设备没插卡时为空列表)
 * @param atMs 收到查询的时刻(ms)
 */
fun DeviceControlModel.withStorageCardQuery(
    cards: List<StorageCard>,
    readings: List<StorageCardReading>,
    atMs: Long,
): DeviceControlModel = copy(
    storageCards = cards,
    storageCardReadings = readings.associateBy { it.cardId },
    storageCardQueriedAtMs = atMs,
    storageCardQueryCount = storageCardQueryCount + 1,
)

/**
 * 记录一次平台下发的 `VideoParamAttribute`(A.2.1.13)—— **唯一的写入口**。
 *
 * 语义是**按码流覆盖**:本次报文里带了哪几路就覆盖哪几路,没带的保持原样
 * (每条 `Item` 描述的是"这一路码流现在该是什么",不是"整份配置就是这些")。
 *
 * ⛔ **空 `Item` 列表时不要调本函数**。标准的 `Item` 是 `minOccurs="0"`,空配置合法,
 * 但设备侧分不清"清空"与"平台没写这段"(A.2.1.13 的注释也承认这个歧义)。
 * 贸然清空会把已经配好的码流抹掉、回读随即退回出厂默认 ——
 * 在平台面板上表现为「刚配好的值自己变回去了」,极难归因。
 * 调用方(`DefaultSystemHandler.handleVideoParamAttribute`)对空配置只记 `lastCommand`。
 */
fun DeviceControlModel.withVideoParamConfig(
    items: List<VideoParamState>,
): DeviceControlModel = copy(videoParams = videoParams + items.associateBy { it.streamNumber })

/**
 * 写入设备配置族里的**一个**类型（GB/T 28181-2022 A.2.3.2）—— 本族**唯一的写入口**。
 *
 * 用法固定为 `model.withDeviceConfig { it.copy(pictureMask = state) }`。
 *
 * ⛔ 为什么不让各 handler 直接 `model.copy(deviceConfigs = model.deviceConfigs.copy(...))`：
 *   1. 那样每个 handler 都要自己拼一次嵌套 `copy`，漏一层就是"改了没生效"且**编译不报错**；
 *   2. 「一次下发只写一个类型」这个约束写在入口上，比散在 8 个 handler 里靠自觉可靠 ——
 *      一次 `update` 只发一次 StateFlow 值，UI 不会看到"半份配置"的中间态。
 *
 * ⛔ 与 [withVideoParamConfig] 一样：**解析被拒（`ConfigParse.Rejected`）时不要调本函数**。
 * 拒收的语义是"这次下发设备没接受"，落任何值都会让回读变成一条设备从未执行过的配置。
 */
fun DeviceControlModel.withDeviceConfig(
    transform: (DeviceConfigState) -> DeviceConfigState,
): DeviceControlModel = copy(deviceConfigs = transform(deviceConfigs))

/**
 * 一条巡航轨迹在**设备侧**的完整配置(GB/T 28181-2022 §9.5.3 / 附录 A.2.6.13-A.2.6.14)。
 *
 * 为什么不是 `List<Int>`:巡航的「速度」和「停留时间」在控制层是**按组下发**的 ——
 * `0x86 设置巡航速度` / `0x87 设置巡航停留时间` 的 PTZCmd 里只有**巡航组号**,
 * 没有点的编号(见 [PtzCmdDecoder] 的字节表)。所以设备只能为**整条轨迹**存一个
 * 速度和停留时间,这也正是查询应答里每个 [CruisePointState] 都回同一个值的原因。
 *
 * 应答报文是**按点**返回 `Speed`/`StayTime` 的,但设备**逐点不同**的参数平台下不下来,
 * 因此这里如实存组级值,不做逐点建模 —— 免得读代码的人以为设备真能逐点配。
 */
data class CruiseTrackState(
    /** 有序预置位编号列表 —— **顺序即巡航顺序**,由平台按 `0x84 加入巡航点` 的先后追出来。 */
    val points: List<Int> = emptyList(),
    /** 组级云台速度,wire 上 `0x86` 的 12 位参数(1-4095)。null = 平台从未下发过速度。 */
    val speed: Int? = null,
    /** 组级停留秒数,wire 上 `0x87` 的 12 位参数(1-4095)。null = 平台从未下发过停留时间。 */
    val dwellTime: Int? = null,
    /**
     * A.2.3.1.2 `PTZCmdParams/CruiseTrackName`（**2022 新增**）—— 平台给这条轨迹起的名字
     * （最长 32 字节）。null = 平台没给过 ⇒ 回读时由设备按 `巡航 N` 兜底。
     *
     * ⛔ 不预填设备自造名：那会让"平台配过名"和"没配过"在设备侧分不出来，
     * 平台也就无法发现"我配的名字没保存"。
     */
    val name: String? = null,
)

/**
 * 一个扫描组在**设备侧**的配置(GB/T 28181-2022 表 A.10 自动扫描)。
 *
 * 三个字段都刻意留 `null` = "平台从未下发过这一项",与"下发过但是这个值"分开 ——
 * 与 [CruiseTrackState] / 看守位同一口径。设备屏幕上要能说出"右边界还没设",
 * 而不是拿一个自己编的默认角度冒充。
 *
 * ⛔ **边界存的是"当时那个点"的完整位姿**(`PtzPose`),因为标准的设边界指令只说
 * 「把当前位置设为左/右边界」,没有数值入参 —— 设备只能快照自己此刻的姿态。
 * 但**横扫只驱动水平轴**:表 A.10 注4 写的是"自动扫描开始时,整体画面从右向左移动",
 * 即水平线扫;俯仰/倍率留在原地不动(真机的线扫也是这个形态)。
 *
 * ⛔ 边界值一律取自设备自己的 `panAngle`,平台**无法**直接写数值进来,也没有任何查询命令
 * 能把它们读回去(附录 A 没有 `ScanQuery`)。所以这一份数据只服务于"设备此刻在扫哪一段"。
 */
data class ScanGroupState(
    /** 左边界快照(表 A.10 序号2,`0x89` 字节6=01H)。null = 平台没设过。 */
    val leftBoundary: PtzPose? = null,
    /** 右边界快照(表 A.10 序号3,`0x89` 字节6=02H)。null = 平台没设过。 */
    val rightBoundary: PtzPose? = null,
    /**
     * 扫描速度,wire 上是 `0x8A` 的 12 位参数(1-4095)。
     *
     * ⚠️ **标准只给了"低 8 位 + 高 4 位"的位置,没给单位** —— 它不是 °/s、也不是档位,
     * 量纲由厂商自定。设备侧如实存原值(查询/回显都报原值),换算成实际转速的地方
     * 只有一处:[com.uvp.sim.domain.scanRateDegPerSec]。null = 平台从未下发过速度。
     */
    val speed: Int? = null,
)

/**
 * 平台控制命令的语义分类(GB-2022 §9.3.4 / 附录 A.3 业务大类)。
 *
 * UI 用它派 Tab,不再 parse [LastDeviceCommand.rawHex] 字符串(轨 ④ PR-UI-PROTOCOL-FIX)。
 * 派生逻辑集中在 [deriveCommandCategory],输入 [LastDeviceCommand.type]/[LastDeviceCommand.rawHex],
 * 输出语义枚举;协议字符串泄露被压缩到这一个函数,不再散落到 Compose 视图里。
 */
enum class DeviceCommandCategory {
    /** PTZ 运动 / 预置位 / 巡航 / 看守位 / 精确定位 / 三维拉框 */
    Ptz,
    /** 录像 / 布防 / 报警 / 远程重启 */
    Status,
    /** 强制 I 帧 / 抓拍 / 拉框聚焦 / 设备配置 / 在线升级 / 格式化 SD / 目标跟踪 */
    Image,
    /** 辅助开关(GB-2022 附录 A.3.7 表 A.11,字节4=0x8C/0x8D):标准只定义编号 1 = 雨刷 */
    Aux,
}

/**
 * UI 渲染派生层 — 仅含「显示用文本 / 时间戳 / 一次性 effect 标志」,由 [deriveRenderState] 从
 * [DeviceControlModel] 纯函数推导(single source of truth = Model)。
 *
 * 设计:UI Mapper / Compose 视图只读 RenderState 里的字段,不再读 Model 内部细节如 lastCommand?.rawHex,
 * 这样 Model 字段调整不破 UI 契约;同时 RenderState 不持有独立可变状态,Model 变 → RenderState 自动跟。
 */
data class DeviceControlRenderState(
    /** 最近一次设备控制命令的类型(显示用),null = 从未收过. */
    val lastCommandType: String? = null,
    /** 最近一次设备控制命令的原文 hex / 简述(显示用). */
    val lastCommandHex: String? = null,
    /** 最近一次设备控制命令接收时间戳(ms,显示运行时长 / 高亮 fade). */
    val lastRecvAtMs: Long? = null,
    /** 最近一次 PTZ 命令解码后的方向 / 速度(HUD 显示用). */
    val lastCommandPtz: PtzCommand? = null,
    /**
     * 最近一次设备控制命令的语义分类(UI Tab 派发用)。null = 从未收过 / 未识别。
     * **轨 ④ PR-UI-PROTOCOL-FIX**:UI 不再 parse rawHex,直接读它分流 Tab。
     */
    val lastCommandCategory: DeviceCommandCategory? = null,
    /** 是否有一次性 effect 待 UI 消费(用于驱动 Snackbar/Flash 动画). */
    val hasPendingEffect: Boolean = false,
    /** 当前一次性 effect(=Model.pendingEffect,UI LaunchedEffect 直接消费). */
    val pendingEffect: DeviceEffect? = null,
    /** 辅助开关最近一次状态变更时间戳(ms),供 UI 显示运行时长. */
    val auxTimestamps: Map<Int, Long> = emptyMap(),
)

/**
 * Model → RenderState 纯函数推导。**禁止**在此函数里读取任何外部状态(no time / no random),
 * 必须保证 `deriveRenderState(m) == deriveRenderState(m)`。
 */
fun deriveRenderState(model: DeviceControlModel): DeviceControlRenderState =
    DeviceControlRenderState(
        lastCommandType = model.lastCommand?.type,
        lastCommandHex = model.lastCommand?.rawHex,
        lastRecvAtMs = model.lastCommand?.timestampMs,
        lastCommandPtz = model.lastCommand?.ptz,
        lastCommandCategory = model.lastCommand?.let { deriveCommandCategory(it) },
        hasPendingEffect = model.pendingEffect != null,
        pendingEffect = model.pendingEffect,
        auxTimestamps = model.auxTimestamps,
    )

/**
 * 把协议层 [LastDeviceCommand] 分到 UI Tab 用的 [DeviceCommandCategory]。
 *
 * 历史:UI 层在 `PtzHudPanel.HudTab.fromCommand` 用 `rawHex.startsWith("雨刷")` 等中文字符串
 * 匹配判 Tab,导致协议层 dispatcher 写啥 UI 必须知道。轨 ④ PR-UI-PROTOCOL-FIX 把这段判别压到这里,
 * UI 只读语义枚举。
 *
 * 规则(同原 HudTab.fromCommand 行为):
 *   - PTZCmd:rawHex 以"雨刷"开头(已映射)或以"Aux"开头(未映射编号)→ [DeviceCommandCategory.Aux];其余 → [Ptz]
 *   - PTZPreciseCtrl → [Ptz]
 *   - RecordCmd / GuardCmd / AlarmCmd / TeleBoot → [Status]
 *   - IFameCmd / IFrameCmd / SnapShotCmd / DeviceConfig / DeviceUpgrade / FormatSDCard / TargetTrack → [Image]
 *   - HomePosition → [Ptz](原 HudTab.fromCommand 未列,但 HomePosition 属云台范畴)
 *   - 其他未知 type → null
 */
fun deriveCommandCategory(cmd: LastDeviceCommand): DeviceCommandCategory? = when (cmd.type) {
    "PTZCmd" -> {
        val raw = cmd.rawHex
        // ⛔ 只认标准命名的「雨刷」+ 未映射编号统一打的「Aux」前缀(见 AuxHandler)。
        //    红外灯/加热/除雾/制冷 已随 AuxFunction 收敛移除(GB/T 28181 A.3.7 只定义编号 1),
        //    不再作为类别判据 —— 判据跟着协议走,不能跟着"曾经写过的厂商标号"走。
        val isAux = raw.startsWith("雨刷") || raw.startsWith("Aux")
        if (isAux) DeviceCommandCategory.Aux else DeviceCommandCategory.Ptz
    }
    "PTZPreciseCtrl", "HomePosition" -> DeviceCommandCategory.Ptz
    "RecordCmd", "GuardCmd", "AlarmCmd", "TeleBoot" -> DeviceCommandCategory.Status
    "IFameCmd", "IFrameCmd", "SnapShotCmd", "DeviceConfig",
    "DeviceUpgrade", "FormatSDCard", "TargetTrack" -> DeviceCommandCategory.Image
    else -> null
}

// ----- Model 复用的领域类型(原放在 DeviceControlState.kt,wrapper 删除后挪到这里) -----

/** GB-2022 §9.13 设备升级进度状态. */
data class UpgradeProgress(
    val sessionId: String,
    val firmware: String,
    val percent: Int,
    val result: UpgradeResult,
)

enum class UpgradeResult { InProgress, Success, Failure }

data class PtzPose(val pan: Float, val tilt: Float, val zoom: Float)

/**
 * 拉框放大/缩小（A.2.3.1.8/.9）报文里的**原始像素**。
 *
 * ⛔ 六个值必须整组收下：只拿到框、没拿到 [frameLength] / [frameWidth]（标准叫
 * `Length` / `Width`，即**播放窗口**的长度/宽度像素值）就**做不了比例换算** ——
 * 标准那句注写得很明确："命令中的坐标系以播放窗口的左上角原点，各坐标取值以像素单位"。
 * 2026-09-20 之前本仓只解析了 4 个框字段，把这两把**尺子**丢在地上，
 * 于是归一化无从谈起、画布上的框也只能靠一个错的口径（0~1000）画。
 *
 * 语义与 [com.uvp.sim.gb28181.TargetArea] 完全同源（元素名都一模一样），差别的只是
 * 那条报文属于哪条命令 —— 所以解析必须**先取自己的块**再在块内找标签。
 */
data class DragZoomRect(
    val midX: Int,
    val midY: Int,
    val lengthX: Int,
    val lengthY: Int,
    /** 标准 `Length` = 播放窗口**长度**像素值（横向尺子）。缺省 0 = 报文里没有。 */
    val frameLength: Int = 0,
    /** 标准 `Width` = 播放窗口**宽度**像素值（纵向尺子）。缺省 0 = 报文里没有。 */
    val frameWidth: Int = 0,
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
    /** 手机端本地模拟位置变化，不表示收到平台 PTZPreciseCtrl。 */
    data class LocalPoseGoto(val targetPose: PtzPose) : DeviceEffect()
    data class ConfigChanged(val changedFields: List<String>) : DeviceEffect()
    /** GB-2022 §9.3.4 DeviceUpgrade — UI snackbar 提示,不真 OTA */
    data class DeviceUpgradeRequested(val firmware: String) : DeviceEffect()
    /**
     * GB-2022 A.2.3.1.13 FormatSDCard — 设备侧**真的**开始格式化(见
     * [VirtualStorageCards.format]),这里这个 effect 只负责让 UI 弹一条提示。
     *
     * [cardIndex] 是**卡号**(标准:元素值即卡号,从 1 开始;**0 = 全部卡**)。
     *
     * ⛔ 2026-09-20 改正:原 KDoc 写的是「UI snackbar 提示,**不真格式化**」——
     *    那就意味着平台下发之后设备侧读数毫无变化,平台观察窗里 `formatting` /
     *    `FormatProgress` / 剩余空间一条都看不到,整条闭环在设备侧断掉。
     */
    data class FormatSDCardRequested(val cardIndex: Int) : DeviceEffect()
}

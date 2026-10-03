package com.uvp.sim.config

import kotlinx.serialization.Serializable

/**
 * Static configuration for one simulator session.
 *
 * The configuration captures everything the SIP layer needs to register and
 * keep alive: server endpoint, device identity, credentials, and timing.
 * The runtime state (current SipState, active calls) is held by SimulatorEngine.
 */
@Serializable
data class GeoPoint(
    val longitude: Double = DEFAULT_LONGITUDE,
    val latitude: Double = DEFAULT_LATITUDE
) {
    companion object {
        /**
         * 默认点位(WGS-84)。`ChannelProfile` 的**安装位置**默认值也指向这两个常量 ——
         * 新设备的"装在哪"与"漫游起点"默认是同一个点,这是最自然的场景;
         * 两处共用常量是为了**不可漂移**:改一处就同时改,不会变成两个不一致的默认值。
         */
        const val DEFAULT_LONGITUDE: Double = 116.404
        const val DEFAULT_LATITUDE: Double = 39.915
    }
}

/** 逻辑协议时钟配置。NTP 默认关闭；关闭或不可用时使用注册 200 OK 的 SIP Date。 */
@Serializable
data class TimeSyncConfig(
    val ntpEnabled: Boolean = false,
    val ntpServer: String = "ntp.aliyun.com",
    val ntpPort: Int = 123,
    val refreshIntervalSeconds: Int = 3600,
)

@Serializable
data class SimConfig(
    val gbVersion: GbVersion = GbVersion.V2022,
    val server: ServerConfig,
    val device: DeviceConfig,
    val transport: com.uvp.sim.network.TransportType =
        com.uvp.sim.network.TransportType.UDP,
    val audioTransport: AudioTransportType = AudioTransportType.TCP_ACTIVE,
    val video: VideoProfile = VideoProfile(),
    val recording: RecordingProfile = RecordingProfile(),
    val expiresSeconds: Int = 3600,
    val keepaliveIntervalSeconds: Int = 60,
    val maxKeepaliveTimeouts: Int = 3,
    /** 附录 M 多响应消息单包记录数。设备端配置，Catalog/RecordInfo 共用。 */
    val multiResponsePageSize: Int = 50,
    /**
     * M-2 (audit §3) — SIP dialog 空闲超时(秒),`<= 0` 关闭 GC。默认 1800s = 30 分钟。
     * 超过该时长无任何 in-dialog 消息(INVITE / re-INVITE / NOTIFY / BYE)的 dialog
     * 视为对端已失联,本端主动清理释放资源。
     */
    val dialogIdleTimeoutSeconds: Int = 1800,
    val userAgent: String = "UVP-Sim/0.1",
    val mockPosition: GeoPoint = GeoPoint(),
    val osd: OsdConfig = OsdConfig(),
    val network: NetworkConfig = NetworkConfig(),
    val timeSync: TimeSyncConfig = TimeSyncConfig(),
    /**
     * GB §9.3.1 设备目录树。空 list 表示由 CatalogTreeStore 从 device 字段
     * 自动生成默认 3 节点扁平树(老 SimConfig 升级路径)。
     */
    val catalogTree: List<CatalogNode> = emptyList(),
    /**
     * P2-6 (audit §3) — 图像抓拍上传配置。
     */
    val snapshot: SnapshotConfig = SnapshotConfig()
) {
    /**
     * 配置是否齐备到可发起 SIP 注册。
     * 任一必填项空 → 注册按钮禁用,避免向上级平台发空字段的 REGISTER。
     * 必填项:服务器 IP / 服务器 ID(20 位国标编码) / 域(10 位前缀) / 设备 ID(20 位国标编码) / 密码;端口由默认值 5060 兜底。
     *
     * R1 #7:补 GB ID 结构校验。原先只校验非空,允许 16 位 / 22 位 / 含字母的乱字符串
     * 进 REGISTER / Catalog / INVITE 路由,平台会拒,但本地配置已"看上去就绪"。
     */
    val isReadyToRegister: Boolean
        get() = server.ip.isNotBlank() &&
            com.uvp.sim.gb28181.IdEncoder.isValidGbId(server.serverId) &&
            com.uvp.sim.gb28181.IdEncoder.isValidGbDomain(server.domain) &&
            com.uvp.sim.gb28181.IdEncoder.isValidGbId(device.deviceId) &&
            device.password.isNotBlank()
}

/**
 * 录像引擎参数 — 默认值对标 plan §4 / §5。
 *
 * 字段含义:
 *   - [quality]:CameraX [androidx.camera.video.Quality] 档位字符串("HD"/"FHD"/"SD")
 *   - [segmentMinutes]:单段录像最长分钟数,超过即切片接力(0 = 关切片)
 *   - [minFreeMb]:磁盘最低剩余 MB,启动时 / 录像中跌破即拒绝/停止
 *   - [playbackAudioCodec]:PLAYBACK 推流时 PsMuxer 的 audio 类型
 */
@Serializable
data class RecordingProfile(
    val quality: String = "HD",
    val segmentMinutes: Int = 30,
    val minFreeMb: Int = 200,
    val playbackAudioCodec: com.uvp.sim.media.AudioCodec = com.uvp.sim.media.AudioCodec.AAC
)

/**
 * Encoding parameters for the camera→encoder pipeline.
 *
 * Defaults match GB28181 commonly-tested baseline:
 *   1280x720 @ 25fps, H.264, 2Mbps, 1s GOP, G.711A audio.
 */
@Serializable
data class VideoProfile(
    val resolution: VideoResolution = VideoResolution.HD_720P,
    val frameRate: Int = 25,
    val bitrateKbps: Int = 2000,
    val keyframeIntervalSeconds: Int = 1,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val audioCodec: AudioCodec = AudioCodec.G711A,
    /**
     * Audio sample rate in Hz. G.711 is fixed at 8000 by ITU-T G.711 standard;
     * AAC supports 8000 / 16000. The UI enforces this constraint by locking
     * the field when codec is G.711.
     */
    val audioSampleRateHz: Int = 16000
) {
    val matchedPreset: VideoQualityPreset?
        get() = VideoQualityPreset.entries.firstOrNull {
            it.resolution == resolution &&
                it.frameRate == frameRate &&
                it.bitrateKbps == bitrateKbps &&
                it.keyframeIntervalSeconds == keyframeIntervalSeconds
        }

    /**
     * Effective audio sample rate after applying codec constraints.
     * G.711 always returns 8000 regardless of stored value.
     */
    val effectiveAudioSampleRateHz: Int
        get() = when (audioCodec) {
            AudioCodec.G711A, AudioCodec.G711U -> 8_000
            AudioCodec.AAC -> audioSampleRateHz
        }
}

/**
 * Curated quality presets — what users actually want when they don't know
 * what to pick. Each preset bakes in resolution / fps / bitrate / GOP; codec
 * choice is orthogonal and stays user-controlled.
 */
enum class VideoQualityPreset(
    val label: String,
    val description: String,
    val resolution: VideoResolution,
    val frameRate: Int,
    val bitrateKbps: Int,
    val keyframeIntervalSeconds: Int = 1
) {
    SMOOTH("流畅", "480P·15fps", VideoResolution.SD_480P, 15, 600),
    STANDARD("标准", "720P·20fps", VideoResolution.HD_720P, 20, 1200),
    HD("高清", "720P·25fps", VideoResolution.HD_720P, 25, 2000),
    UHD("超清", "1080P·25fps", VideoResolution.FHD_1080P, 25, 4000)
}

@Serializable
enum class VideoResolution(val widthPx: Int, val heightPx: Int, val label: String) {
    SD_480P(640, 480, "640×480"),
    HD_720P(1280, 720, "1280×720"),
    FHD_1080P(1920, 1080, "1920×1080");

    /**
     * GB/T 28181 附录 G（2022 版；2016 版为附录 F）SDP `f=` 字段的**分辨率码值**。
     *
     * 标准码表：1=QCIF / 2=CIF / 3=4CIF / 4=D1 / 5=720P / 6=1080P，其余用 `WxH` 形式。
     *
     * ⭐ **这是单一真源。** SDP 协商（[com.uvp.sim.sip.SipHeaderHelpers.buildSdpMediaSpec]）
     * 与配置回读（[com.uvp.sim.gb28181.ConfigDownloadResponse]）必须都读它 ——
     * 曾经两处各写一份 `when`：SDP 侧发码值 `5`、ConfigDownload 侧发人读串 `"720P"`，
     * 同一台设备两条出口不一致，平台侧永远对不上。
     *
     * ⚠️ 待核：`SD_480P -> "4"` 映射到 D1（704×576）并不严谨，640×480 严格说该用
     * `WxH` 形式。此处**保持既有 SDP 行为不变**，疑点另行记录（见
     * `docs/gb28181-2022-video-param-attribute-panel.md` §九）。
     */
    val gb28181Code: Int
        get() = when (this) {
            SD_480P -> 4
            HD_720P -> 5
            FHD_1080P -> 6
        }

    companion object {
        fun from(w: Int, h: Int): VideoResolution =
            entries.firstOrNull { it.widthPx == w && it.heightPx == h } ?: HD_720P
    }
}

/** Video codec selection — defined in the media layer; re-exported here for UI use. */
typealias VideoCodec = com.uvp.sim.media.VideoCodec

/** Audio codec selection — defined in the media layer; re-exported here for UI use. */
typealias AudioCodec = com.uvp.sim.media.AudioCodec

@Serializable
enum class GbVersion(val label: String, val xGbVer: String) {
    V2016("GB/T 28181-2016", "2.0"),
    V2022("GB/T 28181-2022", "3.0")
}

@Serializable
enum class AudioTransportType(val label: String) {
    UDP("UDP"),
    TCP_ACTIVE("TCP 主动"),
    TCP_PASSIVE("TCP 被动")
}

@Serializable
data class ServerConfig(
    val ip: String,
    val port: Int = 5060,
    /** 上级平台编码 / Server ID, e.g. 34020000002000000001 */
    val serverId: String,
    /** SIP domain / realm, e.g. 3402000000 */
    val domain: String,
    /**
     * M-6 (audit §3) — 服务器 IP 白名单。
     *
     * 空 list = 不强制白名单(默认,保兼容老配置);非空时仅当 [ip] 命中白名单
     * 才允许 connect,否则 transport.connect() refuse + SystemLogger Error。
     *
     * 主要场景:LAN 多平台环境下,把模拟器锁定到只允许接特定平台 IP,
     * 防止配置错填 / IP 漂移 / DNS 投毒。条目格式:
     *  - 精确 IP("10.0.0.100")
     *  - CIDR("10.0.0.0/24")— v1 暂不支持 CIDR,只匹配精确 IP,留 hook 后续扩展
     */
    val allowList: List<String> = emptyList(),
)

@Serializable
data class DeviceConfig(
    /** 设备编码 (device ID) — used as From URI user part */
    val deviceId: String,
    /** 设备显示名称 — Catalog Name + 可选 SIP From display name。WVP 后台显示这个 */
    val name: String = "UVP-Sim",
    /** 视频通道编码 — 后置摄像头通道。used in Catalog response and INVITE matching */
    val videoChannelId: String,
    /** 报警通道编码 — used by alarm Notify */
    val alarmChannelId: String,
    /** SIP authentication username (often equals deviceId) */
    val username: String,
    val password: String,
    /**
     * GB/T 28181 §9.3.2 DeviceInfo 应答的"出厂级"标识。
     * 默认即模拟器自报值,UI 设备配置页可改。
     */
    val manufacturer: String = "UVP",
    val model: String = "GB28181-Sim",
    val firmware: String = "0.1.0",
    val hardwareVersion: String = "Mobile",
    /** 通道高级属性 — GB/T 28181-2022 §9.3.1 Catalog 新增字段集合。M2 单通道全部挂这里。 */
    val channel: ChannelProfile = ChannelProfile(),
    /**
     * 双真实通道(dual-camera-channel)— 前置摄像头通道编码。
     * 默认空,运行期由 SipViewModel 用 IdEncoder 按 domain 生成。空时 defaultTree
     * 回退为单(后置)视频通道,兼容老配置。
     */
    val frontChannelId: String = "",
    /** 前置摄像头通道显示名 */
    val frontChannelName: String = "前置摄像头",
    /** 后置摄像头(即 videoChannelId)通道显示名 */
    val videoChannelName: String = "后置摄像头"
) {
    /**
     * 双真实通道映射:被叫 channelId → 摄像头朝向。
     * 仅当 channelId 非空且等于 frontChannelId 时为前置;其余(后置 ID / 未知 / 空)兜底后置。
     * 兜底后置保证老配置(frontChannelId 空)与未知通道行为稳定。
     */
    fun facingForChannel(channelId: String): com.uvp.sim.camera.CameraFacing =
        if (channelId.isNotBlank() && channelId == frontChannelId)
            com.uvp.sim.camera.CameraFacing.FRONT
        else
            com.uvp.sim.camera.CameraFacing.BACK

    /**
     * 被叫 channelId → 该通道的显示名(OSD 通道名层烧戳用)。
     * 前置通道返回 [frontChannelName],其余(后置 / 未知 / 空)返回 [videoChannelName]。
     * 跟 [facingForChannel] 的兜底语义保持一致。
     */
    fun channelNameForChannel(channelId: String): String =
        if (channelId.isNotBlank() && channelId == frontChannelId) frontChannelName
        else videoChannelName
}

/**
 * GB/T 28181 Catalog Item 通道级属性(§9.3.1)。
 *
 * 这些字段是**通道级**属性,不是设备级。M1/M2 阶段只有一个视频通道,先把它平放在
 * [DeviceConfig] 里。后续多通道时再按 channel 拆。
 *
 * ⭐ 输出形态**按有效国标版本分支**(2016 / 2022 两套,见
 * `com.uvp.sim.gb28181.CatalogNotifyBuilder.renderItem`):
 * - **2022**:全部字段进 `<Info>` 容器,另加 `PhotoelectricImagingType` / `CapturePositionType` /
 *   `StreamNumberList`;`BusinessGroupID` 提到 `Item` 层
 * - **2016**:`<Info>` 内不含上述 2022 独有字段,但**多出** [positionType] / [useType]
 *   (这两个 2022 已删除),且 `BusinessGroupID` 仍留在 `<Info>` 内
 * - 两版共用且位置相同:`PTZType` / `RoomType` / `SupplyLightType` / `DirectionType` / `Resolution`
 */
@Serializable
data class ChannelProfile(
    /** 通道接入网络的 IP / 端口(展示给上级平台,默认 0.0.0.0 / 5060,运行期可由 SimulatorEngine 注入实际值) */
    val ipAddress: String = "0.0.0.0",
    val port: Int = 5060,
    val ptzType: PtzType = PtzType.FixedGun,
    /**
     * ⛔ **仅 2016 输出**(2022 已删除该字段)。
     * 默认取 `CentralPlaza`(4) —— 本枚举 2026-09-17 修正值域前旧默认是 `Street`(gbCode 也是 4),
     * 保持 gbCode 不变以避免默认值语义漂移。
     */
    val positionType: PositionType = PositionType.CentralPlaza,
    val roomType: RoomType = RoomType.Outdoor,
    /** ⛔ **仅 2016 输出**(2022 已删除该字段)。 */
    val useType: UseType = UseType.PublicSecurity,
    val supplyLightType: SupplyLightType = SupplyLightType.None,
    val directionType: DirectionType = DirectionType.North,
    /**
     * 光电成像类型(§9.3.1,**2022 新增**)。标准允许**多值** → 写进报文时用 `/` 分割。
     * 手机摄像头默认可见光成像。
     */
    val photoelectricImagingTypes: List<PhotoelectricImagingType> =
        listOf(PhotoelectricImagingType.VisibleLight),
    /**
     * 支持的码流编号列表(§9.3.1,**2022 新增**)。`"0"` = 主码流,`"0/1"` = 主码流 + 子码流 1。
     *
     * ⭐ 默认 **`"0/1"`**（2026-09-19 从 `"0"` 改过来的）。这不是"随手多写一路":
     * 这个字段是设备在**目录**里对平台声明自己支持几段码流的地方，而平台侧
     * 视频参数面板**按这份声明渲染行数**。默认只声明 `"0"` 的后果是：
     * 面板永远只有主码流一行，"主子码流分别配置"这件事**在界面上根本演不出来**。
     * 真 IPC 出厂就同时声明主/子两路，所以 `"0/1"` 才是更真实的默认值。
     *
     * ⚠️ 口径边界：本模拟器**不真的产出两路码流**（`VideoProfile` 只有一份，
     * 点播子码流拿到的画面与主码流相同）。所以声明的意义是
     * 「让码流维度在协议与面板上成立」，不是「真的编了两路」。
     * 子码流的**参数档位**另有独立处理，见 `VideoParamAttribute.defaultFor`。
     *
     * ⚠️ 该字段随 [SimConfig] 持久化：真机上若存档里已经写着 `"0"`，改默认值不会生效 ——
     * 在设置页改任意一项并保存即可把整份配置按新默认重写。
     *
     * ⛔ `CapturePositionType` **不在此处** —— 它取值须符合附录 O(约 9 页的 7 位层次码大表),
     * 值域展开留待 B-5;当前仅支持经 `CatalogNode.fields["CapturePositionType"]` 透传。
     */
    val streamNumberList: String = "0/1",
    /** 字符串如 "1280*720/1920*1080",多分辨率以 / 分隔 */
    val resolution: String = "1280*720",
    /**
     * 通道**安装位置**(§9.3.1 目录项 `<Longitude>` / `<Latitude>`,WGS-84 坐标系)。
     *
     * ⛔ 这跟 `SimConfig.mockPosition` 是**两回事**,别混:
     * - 本字段 = 摄像机装在哪 —— 走 **Catalog** 应答,一次声明长期不变;
     * - `mockPosition` = 设备此刻在哪 —— 走 **MobilePosition** 订阅 NOTIFY,持续变化。
     *   两者进平台落的是同一对 `gb_channel.longitude/latitude` 列,但来源标记不同
     *   (`catalog` vs `mobile`),所以"装在哪"和"在哪"才能各自说清楚。
     *
     * 默认值刻意与 `GeoPoint()` 的默认点一致 —— 设备装在它漫游的起点上,这是最自然的场景;
     * 改成别的值也不算错,只是"报的安装位置"与"漫游中心"分离了。
     *
     * ⛔ **0 表示"本端不声明安装位置"**(与平台侧 `hasUsableCoordinate` 的判据一致) ——
     * 此时 `<Longitude>`/`<Latitude>` **两个元素都不输出**。标准里这两个是可选元素
     * (`minOccurs=0`),不发就是不发,不能发 0;平台上 0 会被判成"无坐标"而根本不落库。
     */
    val longitude: Double = GeoPoint.DEFAULT_LONGITUDE,
    val latitude: Double = GeoPoint.DEFAULT_LATITUDE,
    /** 业务分组 ID,默认空字符串(平台一般可空) */
    val businessGroupId: String = ""
)

/**
 * §9.3.1 PTZType 摄像机结构类型。
 * - 2016 值域 1-4；2022 扩到 1-7（新增遥控半球 / 多目设备的全景拼接通道与分割通道）
 * - [Unsupported] 的 `0` **两版标准都没有**，是模拟器内部占位值 → 渲染时该值**不输出** PTZType 元素
 */
@Serializable
enum class PtzType(val gbCode: Int, val label: String) {
    Unsupported(0, "不支持"),
    Dome(1, "球机"),
    HalfDome(2, "半球"),
    FixedGun(3, "固定枪机"),
    RemoteGun(4, "遥控枪机"),
    RemoteHalfDome(5, "遥控半球"),
    MultiLensPanorama(6, "多目设备全景/拼接通道"),
    MultiLensSplit(7, "多目设备分割通道")
}

/**
 * §9.3.1 PositionType 摄像机位置类型扩展 —— ⛔ **仅 2016 有，2022 已删除该字段**。
 *
 * 值域照 2016 附录 A 原文（标准页 52）：
 * 1-省际检查站 / 2-党政机关 / 3-车站码头 / 4-中心广场 / 5-体育场馆 /
 * 6-商业中心 / 7-宗教场所 / 8-校园周边 / 9-治安复杂区域 / 10-交通干线。
 *
 * ⛔ 别按「省级/市级/区县级/街道级监控点」那套平台侧分类理解 —— 标准里没有那个口径，
 * 本枚举 2026-09-17 前正是写成了那套（发出去的值对不上标准）。
 */
@Serializable
enum class PositionType(val gbCode: Int, val label: String) {
    Checkpoint(1, "省际检查站"),
    Government(2, "党政机关"),
    StationQuay(3, "车站码头"),
    CentralPlaza(4, "中心广场"),
    Stadium(5, "体育场馆"),
    BusinessCenter(6, "商业中心"),
    ReligiousSite(7, "宗教场所"),
    Campus(8, "校园周边"),
    ComplexArea(9, "治安复杂区域"),
    TrafficArtery(10, "交通干线")
}

/**
 * §9.3.1 RoomType 摄像机安装位置。2016/2022 同值域 ——
 * ⛔ **1 = 室外、2 = 室内**（缺省 1）。
 * 本枚举 2026-09-17 前把两者写反了（Indoor=1 / Outdoor=2），会直接发出错误语义，已按标准修正。
 */
@Serializable
enum class RoomType(val gbCode: Int, val label: String) {
    Outdoor(1, "室外"),
    Indoor(2, "室内")
}

/**
 * §9.3.1 UseType 摄像机用途。1-治安 / 2-交通 / 3-重点。
 * ⛔ **仅 2016 有，2022 已删除该字段**。
 */
@Serializable
enum class UseType(val gbCode: Int, val label: String) {
    PublicSecurity(1, "治安"),
    Traffic(2, "交通"),
    Important(3, "重点")
}

/**
 * §9.3.1 SupplyLightType 摄像机补光属性。
 * 2016：1-无补光 / 2-红外补光 / 3-白光补光；2022 增设 4-激光补光 / 9-其他。
 */
@Serializable
enum class SupplyLightType(val gbCode: Int, val label: String) {
    None(1, "无补光"),
    Infrared(2, "红外补光"),
    White(3, "白光补光"),
    Laser(4, "激光补光"),
    Other(9, "其他")
}

/**
 * §9.3.1 PhotoelectricImagingType 摄像机光电成像类型 —— **2022 新增字段**(2016 无)。
 * 1-可见光成像 / 2-热成像 / 3-雷达成像 / 4-X光成像 / 5-深度光场成像 / 9-其他。
 * 标准允许**多值** → 写进报文时用英文半角 `/` 分割。
 */
@Serializable
enum class PhotoelectricImagingType(val gbCode: Int, val label: String) {
    VisibleLight(1, "可见光成像"),
    Thermal(2, "热成像"),
    Radar(3, "雷达成像"),
    XRay(4, "X光成像"),
    DepthLightField(5, "深度光场成像"),
    Other(9, "其他")
}

/** §9.3.1 DirectionType 摄像机监视方位。1-东 / 2-西 / 3-南 / 4-北 / 5-东南 / 6-东北 / 7-西南 / 8-西北。两版相同。 */
@Serializable
enum class DirectionType(val gbCode: Int, val label: String) {
    East(1, "东"),
    West(2, "西"),
    South(3, "南"),
    North(4, "北"),
    Southeast(5, "东南"),
    Northeast(6, "东北"),
    Southwest(7, "西南"),
    Northwest(8, "西北")
}

/**
 * P2-6 (audit §3) — 图像抓拍上传安全配置。
 *
 * [uploadAllowList] 平台下发 SnapShotConfig.uploadUrl 的 host 白名单。
 * - 空 list = **零信任默认,拒绝任意 URL**(避免真实抓拍接通后立刻 SSRF / 数据外传)
 * - 非空时仅当 uploadUrl 的 host 精确匹配白名单中某条目时才允许上传
 * - v1 暂用精确字面量匹配,不支持 CIDR / 通配符
 *
 * 典型场景:在配置页手动添加 `["192.168.1.10", "platform.example.com"]`,
 * 拒绝 loopback / link-local / multicast / 云厂商元数据地址。
 */
@Serializable
data class SnapshotConfig(
    val uploadAllowList: List<String> = emptyList()
)

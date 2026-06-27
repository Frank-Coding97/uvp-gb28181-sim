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
    val longitude: Double = 116.404,
    val latitude: Double = 39.915
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
     * 必填项:服务器 IP / 服务器 ID / 域 / 设备 ID / 密码;端口由默认值 5060 兜底。
     */
    val isReadyToRegister: Boolean
        get() = server.ip.isNotBlank() &&
            server.serverId.isNotBlank() &&
            server.domain.isNotBlank() &&
            device.deviceId.isNotBlank() &&
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
enum class GbVersion(val label: String) {
    V2016("GB/T 28181-2016"),
    V2022("GB/T 28181-2022")
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
 * GB/T 28181-2022 Catalog Item 新增字段集合(§9.3.1)。
 *
 * 这些字段是**通道级**属性,不是设备级。M1/M2 阶段只有一个视频通道,先把它平放在
 * [DeviceConfig] 里。后续多通道时再按 channel 拆。
 *
 * V2016 模式下这些字段不输出(由 [com.uvp.sim.gb28181.CatalogResponse] 按 gbVersion 决定)。
 */
@Serializable
data class ChannelProfile(
    /** 通道接入网络的 IP / 端口(展示给上级平台,默认 0.0.0.0 / 5060,运行期可由 SimulatorEngine 注入实际值) */
    val ipAddress: String = "0.0.0.0",
    val port: Int = 5060,
    val ptzType: PtzType = PtzType.FixedGun,
    val positionType: PositionType = PositionType.Street,
    val roomType: RoomType = RoomType.Outdoor,
    val useType: UseType = UseType.PublicSecurity,
    val supplyLightType: SupplyLightType = SupplyLightType.None,
    val directionType: DirectionType = DirectionType.North,
    /** 字符串如 "1280*720/1920*1080",多分辨率以 / 分隔 */
    val resolution: String = "1280*720",
    /** 业务分组 ID,默认空字符串(平台一般可空) */
    val businessGroupId: String = ""
)

/** §9.3.1 PTZType:0 不支持 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机 */
@Serializable
enum class PtzType(val gbCode: Int, val label: String) {
    Unsupported(0, "不支持"),
    Dome(1, "球机"),
    HalfDome(2, "半球"),
    FixedGun(3, "固定枪机"),
    RemoteGun(4, "遥控枪机")
}

/** §9.3.1 PositionType:1 省级 / 2 市级 / 3 区县级 / 4 街道级 / 5 关键节点 */
@Serializable
enum class PositionType(val gbCode: Int, val label: String) {
    Province(1, "省级监控点"),
    City(2, "市级监控点"),
    District(3, "区县级监控点"),
    Street(4, "街道级监控点"),
    KeyNode(5, "关键节点")
}

/** §9.3.1 RoomType:1 室内 / 2 室外 */
@Serializable
enum class RoomType(val gbCode: Int, val label: String) {
    Indoor(1, "室内"),
    Outdoor(2, "室外")
}

/** §9.3.1 UseType:1 治安 / 2 交通 / 3 重点 */
@Serializable
enum class UseType(val gbCode: Int, val label: String) {
    PublicSecurity(1, "治安"),
    Traffic(2, "交通"),
    Important(3, "重点")
}

/** §9.3.1 SupplyLightType:1 无补光 / 2 红外补光 / 3 白光补光 */
@Serializable
enum class SupplyLightType(val gbCode: Int, val label: String) {
    None(1, "无补光"),
    Infrared(2, "红外补光"),
    White(3, "白光补光")
}

/** §9.3.1 DirectionType:1 东 / 2 西 / 3 南 / 4 北 / 5 东南 / 6 东北 / 7 西南 / 8 西北 */
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

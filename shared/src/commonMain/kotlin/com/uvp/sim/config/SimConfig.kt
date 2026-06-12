package com.uvp.sim.config

/**
 * Static configuration for one simulator session.
 *
 * The configuration captures everything the SIP layer needs to register and
 * keep alive: server endpoint, device identity, credentials, and timing.
 * The runtime state (current SipState, active calls) is held by SimulatorEngine.
 */
data class SimConfig(
    val gbVersion: GbVersion = GbVersion.V2022,
    val server: ServerConfig,
    val device: DeviceConfig,
    val transport: com.uvp.sim.network.TransportType =
        com.uvp.sim.network.TransportType.UDP,
    val audioTransport: AudioTransportType = AudioTransportType.UDP,
    val video: VideoProfile = VideoProfile(),
    val expiresSeconds: Int = 3600,
    val keepaliveIntervalSeconds: Int = 60,
    val maxKeepaliveTimeouts: Int = 3,
    val userAgent: String = "UVP-Sim/0.1"
)

/**
 * Encoding parameters for the camera→encoder pipeline.
 *
 * Defaults match GB28181 commonly-tested baseline:
 *   1280x720 @ 25fps, H.264, 2Mbps, 1s GOP, G.711A audio.
 *
 * M1 only the resolution / fps / bitrate / GOP fields are wired through to
 * [com.uvp.sim.camera.CaptureConfig]; videoCodec / audioCodec carry data the
 * UI shows but the pipeline still encodes H.264 only — switching codecs lands
 * in T-VS2 / T-VS3.
 */
data class VideoProfile(
    val resolution: VideoResolution = VideoResolution.HD_720P,
    val frameRate: Int = 25,
    val bitrateKbps: Int = 2000,
    val keyframeIntervalSeconds: Int = 1,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val audioCodec: AudioCodec = AudioCodec.G711A
)

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

enum class GbVersion(val label: String) {
    V2016("GB/T 28181-2016"),
    V2022("GB/T 28181-2022")
}

enum class AudioTransportType(val label: String) {
    UDP("UDP"),
    TCP_ACTIVE("TCP 主动"),
    TCP_PASSIVE("TCP 被动")
}

data class ServerConfig(
    val ip: String,
    val port: Int = 5060,
    /** 上级平台编码 / Server ID, e.g. 34020000002000000001 */
    val serverId: String,
    /** SIP domain / realm, e.g. 3402000000 */
    val domain: String
)

data class DeviceConfig(
    /** 设备编码 (device ID) — used as From URI user part */
    val deviceId: String,
    /** 视频通道编码 — used in Catalog response and INVITE matching */
    val videoChannelId: String,
    /** 报警通道编码 — used by alarm Notify */
    val alarmChannelId: String,
    /** SIP authentication username (often equals deviceId) */
    val username: String,
    val password: String
)

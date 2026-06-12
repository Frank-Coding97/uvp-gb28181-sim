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
data class SimConfig(
    val gbVersion: GbVersion = GbVersion.V2022,
    val server: ServerConfig,
    val device: DeviceConfig,
    val transport: com.uvp.sim.network.TransportType =
        com.uvp.sim.network.TransportType.UDP,
    val audioTransport: AudioTransportType = AudioTransportType.TCP_ACTIVE,
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
    val domain: String
)

@Serializable
data class DeviceConfig(
    /** 设备编码 (device ID) — used as From URI user part */
    val deviceId: String,
    /** 设备显示名称 — Catalog Name + 可选 SIP From display name。WVP 后台显示这个 */
    val name: String = "UVP-Sim",
    /** 视频通道编码 — used in Catalog response and INVITE matching */
    val videoChannelId: String,
    /** 报警通道编码 — used by alarm Notify */
    val alarmChannelId: String,
    /** SIP authentication username (often equals deviceId) */
    val username: String,
    val password: String
)

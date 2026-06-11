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
    val expiresSeconds: Int = 3600,
    val keepaliveIntervalSeconds: Int = 60,
    val maxKeepaliveTimeouts: Int = 3,
    val userAgent: String = "UVP-Sim/0.1"
)

enum class GbVersion(val label: String) {
    V2016("GB/T 28181-2016"),
    V2022("GB/T 28181-2022")
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

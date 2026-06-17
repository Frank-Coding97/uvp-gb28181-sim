package com.uvp.sim.config

import kotlinx.serialization.Serializable

/**
 * 用户网络偏好 — 决定 SIP/RTP socket 走哪张网卡。
 *
 * Android 上由 `NetworkController` 通过 `ConnectivityManager.bindProcessToNetwork`
 * 进程级绑定生效;iOS 上不支持(no-op,实际值始终是 AUTO 的行为)。
 */
@Serializable
enum class NetworkPreference {
    /** 不绑定,系统路由表决定 — 等价于历史行为。 */
    AUTO,

    /** 强制走 Wi-Fi 网卡;无 Wi-Fi 时 UI 报"不可用",不静默回落。 */
    WIFI,

    /** 强制走蜂窝网卡;无 SIM / 飞行模式时 UI 报"不可用",不静默回落。 */
    CELLULAR,
}

/**
 * 网络选择配置 — 跟 [DeviceConfig] / [ServerConfig] / [OsdConfig] 并列,
 * 挂在 [SimConfig] 顶层。
 *
 * 设计原因:这是设备**运行时**选项(可中途切换),不是设备身份属性,
 * 因此独立成顶层 config 而不是塞 DeviceConfig。
 */
@Serializable
data class NetworkConfig(
    val preference: NetworkPreference = NetworkPreference.AUTO,
)

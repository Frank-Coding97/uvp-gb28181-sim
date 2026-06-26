package com.uvp.sim.config

import kotlinx.serialization.Serializable

/**
 * 用户网络偏好 — 决定 SIP/RTP socket 走哪张网卡。
 *
 * PR-A T1.3: 定义已搬到 [com.uvp.sim.api.NetworkPreference] 作为 UI 友好公开 API.
 * 这里保留 typealias 兜底, shared 内部代码 import com.uvp.sim.config.NetworkPreference
 * 仍可解析, 无需触发大面积修改.
 *
 * Android 上由 `NetworkController` 通过 `ConnectivityManager.bindProcessToNetwork`
 * 进程级绑定生效;iOS 上不支持(no-op,实际值始终是 AUTO 的行为)。
 */
typealias NetworkPreference = com.uvp.sim.api.NetworkPreference

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

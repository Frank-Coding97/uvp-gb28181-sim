package com.uvp.sim.network

import com.uvp.sim.config.NetworkPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS no-op 实现 — 状态永远是 [NetworkState.Auto]。
 *
 * iOS 系统不允许应用强制选网卡(NWParameters 只能表达偏好不能硬绑),
 * 所以这里 apply() 不做任何事。UI 层(SettingsScreen)会拦住 iOS 不进网络子页。
 */
actual class NetworkController {

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Auto)
    actual val state: StateFlow<NetworkState> = _state.asStateFlow()

    actual suspend fun apply(preference: NetworkPreference) {
        // no-op
    }

    actual suspend fun close() {
        // no-op
    }
}

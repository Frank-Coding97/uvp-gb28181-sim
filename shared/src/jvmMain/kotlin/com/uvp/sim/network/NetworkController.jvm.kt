package com.uvp.sim.network

import com.uvp.sim.config.NetworkPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM(desktop / 单元测试)no-op 实现。
 *
 * 桌面端不存在"切手机网卡"概念,state 永远是 [NetworkState.Auto]。
 * 主要服务 commonTest 跑 SimulatorEngine 时不需要真切网卡。
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

package com.uvp.sim.ui.model

/**
 * UI 层 网络状态 DTO. 1:1 映射 com.uvp.sim.network.NetworkState.
 * preference 字段直接持有 com.uvp.sim.config.NetworkPreference (B 档,T1.3 之后 typealias = api.NetworkPreference).
 * T-A5 时 UI import 切到 api.* 路径.
 */
sealed class NetworkStateDto {
    data object Auto : NetworkStateDto()
    data class Bound(
        val preference: com.uvp.sim.config.NetworkPreference,
        val interfaceName: String,
        val localIp: String,
    ) : NetworkStateDto()
    data class Unavailable(
        val preference: com.uvp.sim.config.NetworkPreference,
        val reason: String,
    ) : NetworkStateDto()
    data class Switching(
        val from: com.uvp.sim.config.NetworkPreference,
        val to: com.uvp.sim.config.NetworkPreference,
    ) : NetworkStateDto()
}

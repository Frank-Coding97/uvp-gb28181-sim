package com.uvp.sim.ui.model.mapper

import com.uvp.sim.network.NetworkState
import com.uvp.sim.ui.model.NetworkStateDto

/** PR-A T4.2 实现. sealed 4 variant. preference 引用 api.NetworkPreference (B 档,直传). */
fun NetworkState.toDto(): NetworkStateDto = when (this) {
    NetworkState.Auto -> NetworkStateDto.Auto
    is NetworkState.Bound -> NetworkStateDto.Bound(preference, interfaceName, localIp)
    is NetworkState.Unavailable -> NetworkStateDto.Unavailable(preference, reason)
    is NetworkState.Switching -> NetworkStateDto.Switching(from, to)
}

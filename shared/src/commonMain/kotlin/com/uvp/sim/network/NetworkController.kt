package com.uvp.sim.network

import com.uvp.sim.config.NetworkPreference
import kotlinx.coroutines.flow.StateFlow

/**
 * 跨平台网络控制器 — 用户偏好(AUTO / WIFI / CELLULAR)的执行者。
 *
 * Android actual: 用 `ConnectivityManager.requestNetwork` 申请指定 transport
 *                 的网卡,onAvailable 时 `bindProcessToNetwork(network)` 进程级绑定。
 *                 进程内所有 java.nio socket(包括 Ktor 的 SIP/RTP)自动跟。
 *
 * iOS actual: no-op。iOS 系统不允许应用强制选网卡(NWParameters 只能表达偏好不能硬绑),
 *             所以 state 永远是 Auto。UI 层会拦住 iOS 不让进网络设置页。
 *
 * 生命周期:
 *   - 跟 SipViewModel / Activity 同生命周期
 *   - close() 会撤销 networkCallback 并解除进程绑定(回到系统默认)
 */
expect class NetworkController() {

    /** 当前网络状态 — UI 和 SimulatorEngine 共同 collect 这个 flow。 */
    val state: StateFlow<NetworkState>

    /**
     * 应用新的网络偏好。
     *
     * - AUTO: 撤销 callback + 解除进程绑定,state → Auto
     * - WIFI / CELLULAR: 撤销旧 callback + requestNetwork(transport, cb, 8000ms 超时)
     *   * onAvailable → bindProcessToNetwork + state → Bound
     *   * onUnavailable / onLost → state → Unavailable
     *
     * 多次调用此方法是安全的(撤销旧的再申请新的)。
     */
    suspend fun apply(preference: NetworkPreference)

    /** 撤销 callback + 解除进程绑定,常用于 Activity onDestroy。 */
    suspend fun close()
}

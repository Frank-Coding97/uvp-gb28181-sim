package com.uvp.sim.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android actual — 用 ConnectivityManager 实现进程级网卡绑定。
 *
 * 工作流程:
 *   1. apply(WIFI / CELLULAR) → requestNetwork(transport, callback, 8000ms timeout)
 *   2. onAvailable(network) → cm.bindProcessToNetwork(network) 进程级绑死;
 *      读 LinkProperties 拿接口名 + IPv4 → state = Bound
 *   3. onLost / onUnavailable → bindProcessToNetwork(null) → state = Unavailable
 *   4. apply(AUTO) 或 close() → unregisterNetworkCallback + bindProcessToNetwork(null)
 *
 * 进程级绑定的好处:进程内所有 java.nio socket(Ktor 的 SIP/RTP)自动走该网卡,
 * 不需要逐 socket 调 network.bindSocket(socket)(Ktor 不暴露底层句柄)。
 *
 * 权限:INTERNET + ACCESS_NETWORK_STATE(Manifest 已有,运行时无需请求)。
 */
actual class NetworkController actual constructor() {

    @Volatile
    private var cm: ConnectivityManager? = null

    /**
     * 在 Android 上必须先 attach Context 才能 apply 网络偏好。
     * SipViewModel 在构造时调用一次。Context 弱引用风险:这里持有 ApplicationContext
     * (调用方传 `context.applicationContext`),不会泄漏 Activity。
     */
    fun attach(context: Context) {
        cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Auto)
    actual val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var currentCallback: ConnectivityManager.NetworkCallback? = null
    private var currentPreference: NetworkPreference = NetworkPreference.AUTO

    actual suspend fun apply(preference: NetworkPreference) {
        val service = cm
        if (service == null) {
            // 没 attach 就调 → 跟 JVM/iOS 一样 no-op,记日志提醒
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "NetworkController.apply 调用时未 attach Context,跳过(应在 SipViewModel 构造后立即 attach)"
            )
            return
        }
        val from = currentPreference
        teardownCurrent(service)
        currentPreference = preference

        when (preference) {
            NetworkPreference.AUTO -> {
                _state.value = NetworkState.Auto
                SystemLogger.emit(LogLevel.Info, LogTag.Network, "网络偏好 → 自动(解除绑定)")
            }
            NetworkPreference.WIFI, NetworkPreference.CELLULAR -> {
                _state.value = NetworkState.Switching(from = from, to = preference)
                requestSpecific(service, preference)
            }
        }
    }

    actual suspend fun close() {
        cm?.let { teardownCurrent(it) }
        currentPreference = NetworkPreference.AUTO
    }

    // —— internal —— //

    private fun requestSpecific(service: ConnectivityManager, pref: NetworkPreference) {
        val transport = when (pref) {
            NetworkPreference.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            NetworkPreference.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            else -> error("AUTO handled by apply()")
        }
        val req = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val ok = bindProcessSafe(service, network)
                if (!ok) {
                    _state.value = NetworkState.Unavailable(pref, "bindProcessToNetwork 失败")
                    SystemLogger.emit(
                        LogLevel.Error, LogTag.Network,
                        "网络可用但绑定失败 pref=$pref network=$network"
                    )
                    return
                }
                val (ifName, ip) = readInterfaceInfo(service, network)
                _state.value = NetworkState.Bound(pref, ifName, ip)
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Network,
                    "网络已绑定 pref=$pref 接口=$ifName IP=$ip"
                )
            }

            override fun onLost(network: Network) {
                bindProcessSafe(service, null)
                _state.value = NetworkState.Unavailable(pref, reasonOf(pref, kind = "lost"))
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Network,
                    "网络已断开 pref=$pref"
                )
            }

            override fun onUnavailable() {
                _state.value = NetworkState.Unavailable(pref, reasonOf(pref, kind = "timeout"))
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Network,
                    "网络申请超时(8s)pref=$pref"
                )
            }
        }
        currentCallback = cb
        runCatching {
            service.requestNetwork(req, cb, /* timeoutMs */ 8000)
        }.onFailure { e ->
            _state.value = NetworkState.Unavailable(pref, "requestNetwork 异常: ${e.message}")
            SystemLogger.emit(
                LogLevel.Error, LogTag.Network,
                "requestNetwork 异常 pref=$pref: ${e::class.simpleName}: ${e.message}"
            )
            currentCallback = null
        }
    }

    private fun teardownCurrent(service: ConnectivityManager) {
        currentCallback?.let { cb ->
            runCatching { service.unregisterNetworkCallback(cb) }
        }
        currentCallback = null
        bindProcessSafe(service, null)
    }

    private fun bindProcessSafe(service: ConnectivityManager, network: Network?): Boolean {
        return runCatching {
            service.bindProcessToNetwork(network)
        }.getOrElse {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Network,
                "bindProcessToNetwork 异常: ${it::class.simpleName}: ${it.message}"
            )
            false
        }
    }

    private fun readInterfaceInfo(service: ConnectivityManager, network: Network): Pair<String, String> {
        val lp: LinkProperties? = runCatching { service.getLinkProperties(network) }.getOrNull()
        val ifName = lp?.interfaceName ?: "?"
        val ip = lp?.linkAddresses
            ?.firstOrNull { addr ->
                val host = addr.address.hostAddress ?: ""
                // 优先 IPv4(短地址、SIP Contact 头通用)
                host.isNotEmpty() && !host.contains(":")
            }
            ?.address?.hostAddress ?: "0.0.0.0"
        return ifName to ip
    }

    private fun reasonOf(pref: NetworkPreference, kind: String): String = when {
        pref == NetworkPreference.WIFI && kind == "lost" -> "Wi-Fi 已断开"
        pref == NetworkPreference.WIFI && kind == "timeout" -> "Wi-Fi 不可用"
        pref == NetworkPreference.CELLULAR && kind == "lost" -> "蜂窝已断开"
        pref == NetworkPreference.CELLULAR && kind == "timeout" -> "蜂窝不可用(无 SIM 或飞行模式)"
        else -> "网络不可用"
    }
}

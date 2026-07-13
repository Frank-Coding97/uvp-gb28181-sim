package com.uvp.sim.network

import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_loopback
import platform.Network.nw_interface_type_other
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_t
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_queue_create

/**
 * iOS actual — 用 NWPathMonitor 监听系统路径,把当前活跃 transport 反馈到 [state]。
 *
 * **重要差异**(相对 Android):
 *   - iOS 系统层面不允许应用**强制**选网卡(NWParameters 的
 *     `nw_parameters_require_interface_type` 只能作为"偏好",系统仍可无视),
 *     Ktor 也不暴露 socket 底层句柄。所以 [apply] 不做真正的绑定,只记录用户
 *     偏好、驱动 state flow。
 *   - state 反映**当前系统活跃 transport**(不管用户 pref 是啥):
 *     * 系统 path satisfied 且 uses WIFI → Bound(preference, "wifi", "")
 *     * 系统 path satisfied 且 uses CELLULAR → Bound(..., "cellular", "")
 *     * 系统 path satisfied 且都不是 → Bound(..., "other", "")
 *     * 系统 path 不 satisfied → Unavailable(pref, reason)
 *   - localIp 通过 getifaddrs 读取当前活跃接口 IPv4。iOS 不强制绑定 socket,
 *     但对外诊断和 SIP Contact fallback 应报告真实活跃身份。
 *
 * 让 UI 从 no-op 升级到"能感知当前活跃网卡类型"。
 *
 * 生命周期:
 *   - init 里创建 nw_path_monitor,派发 update_handler 到内部 dispatch queue
 *   - close() 取消 monitor,state 回 Auto
 */
@OptIn(ExperimentalForeignApi::class)
actual class NetworkController actual constructor() {

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Auto)
    actual val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val queue = dispatch_queue_create("com.uvp.sim.network.monitor", null)
    private val monitor: nw_path_monitor_t? = nw_path_monitor_create()

    /** 当前用户偏好 — 影响 Unavailable 时的 preference 字段;不影响系统实际路径。 */
    @Volatile
    private var currentPreference: NetworkPreference = NetworkPreference.AUTO

    init {
        val m = monitor
        if (m != null) {
            nw_path_monitor_set_queue(m, queue)
            nw_path_monitor_set_update_handler(m) { path: nw_path_t? ->
                handlePathUpdate(path)
            }
            nw_path_monitor_start(m)
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "iOS NetworkController 启动 — NWPathMonitor 监听中"
            )
        } else {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "iOS NetworkController: nw_path_monitor_create 返回 null,降级 no-op"
            )
        }
    }

    actual suspend fun apply(preference: NetworkPreference) {
        // iOS 不支持强制绑定网卡,只记录偏好,让 handlePathUpdate 里 emit 时用
        currentPreference = preference
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "iOS NetworkController.apply pref=$preference(iOS 只记录偏好,实际路径由系统决定)"
        )
        // 触发一次基于当前 state 的 pref 更新(不重跑 monitor,直接改 state 里的 preference 字段)
        _state.value = when (val s = _state.value) {
            is NetworkState.Bound -> s.copy(preference = preference)
            is NetworkState.Unavailable -> s.copy(preference = preference)
            NetworkState.Auto -> NetworkState.Auto
            is NetworkState.Switching -> s
        }
    }

    actual suspend fun close() {
        monitor?.let { nw_path_monitor_cancel(it) }
        _state.value = NetworkState.Auto
        SystemLogger.emit(LogLevel.Info, LogTag.Network, "iOS NetworkController 已 close")
    }

    // —— internal —— //

    private fun handlePathUpdate(path: nw_path_t?) {
        if (path == null) {
            _state.value = NetworkState.Auto
            return
        }
        val status = nw_path_get_status(path)
        if (status != nw_path_status_satisfied) {
            _state.value = NetworkState.Unavailable(
                preference = currentPreference,
                reason = "系统路径不 satisfied(status=$status)"
            )
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "iOS 路径不可用 pref=$currentPreference status=$status"
            )
            return
        }

        val ifName = when {
            nw_path_uses_interface_type(path, nw_interface_type_wifi) -> "wifi"
            nw_path_uses_interface_type(path, nw_interface_type_cellular) -> "cellular"
            nw_path_uses_interface_type(path, nw_interface_type_wired) -> "wired"
            nw_path_uses_interface_type(path, nw_interface_type_loopback) -> "loopback"
            nw_path_uses_interface_type(path, nw_interface_type_other) -> "other"
            else -> "unknown"
        }
        val localIp = IosLocalIpProvider.refresh(ifName).orEmpty()

        _state.value = NetworkState.Bound(
            preference = currentPreference,
            interfaceName = ifName,
            localIp = localIp
        )
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "iOS 路径已连接 pref=$currentPreference 类型=$ifName ip=${localIp.ifBlank { "unknown" }}"
        )
    }
}

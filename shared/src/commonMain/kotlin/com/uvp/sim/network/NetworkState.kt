package com.uvp.sim.network

import com.uvp.sim.config.NetworkPreference

/**
 * 当前网络绑定状态 — 由 [NetworkController] 维护并通过 StateFlow 暴露。
 *
 * UI 层 collect 这个 state 决定:
 *   - 主屏顶 banner 是否显示([Unavailable] 时显示红 banner)
 *   - 网络设置页诊断区显示当前接口名 / IP
 *
 * SimulatorEngine collect 这个 state 决定:
 *   - [Bound] / [Auto] 触发 unregister → register 序列(Contact 头刷新 IP)
 *   - [Unavailable] 不主动 unregister(发不出去),emit NetworkUnavailable 事件
 *   - [Switching] 不动作(UI 用)
 */
sealed class NetworkState {
    /** 自动 — 系统路由表决定,等价于 feature 落地前的历史行为。 */
    object Auto : NetworkState()

    /**
     * 已绑定到指定网卡。
     * @param interfaceName 接口名,如 "wlan0" / "rmnet_data0"(诊断用,UI 显示)
     * @param localIp 该网卡上设备的 IPv4 地址 — SIP Contact 头要用这个
     */
    data class Bound(
        val preference: NetworkPreference,
        val interfaceName: String,
        val localIp: String,
    ) : NetworkState()

    /**
     * 老板选了 [WIFI] 或 [CELLULAR],但系统申请该 transport 失败
     * (无 Wi-Fi / 无 SIM / 飞行模式 / requestNetwork 超时)。
     *
     * 不静默回落到自动 — 测试工具应该明确报错。
     */
    data class Unavailable(
        val preference: NetworkPreference,
        val reason: String,
    ) : NetworkState()

    /** 正在从 [from] 切到 [to],UI 显示 spinner;Engine 不动作。 */
    data class Switching(
        val from: NetworkPreference,
        val to: NetworkPreference,
    ) : NetworkState()
}

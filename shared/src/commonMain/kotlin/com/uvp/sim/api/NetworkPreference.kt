package com.uvp.sim.api

import kotlinx.serialization.Serializable

/**
 * 用户网络偏好 — 决定 SIP/RTP socket 走哪张网卡。
 *
 * UI 友好公开 API (PR-A T1.3 缩水版搬家). 旧位置 com.uvp.sim.config.NetworkPreference
 * 通过 typealias 兜底, shared 内部代码零侵入.
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

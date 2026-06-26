package com.uvp.sim.ui.actions

import com.uvp.sim.config.NetworkPreference

/**
 * 网络运行时控制动作 — slice 4/4(PR-B)。
 *
 * 范围:NetworkController 偏好切换(Android-only,iOS / JVM no-op)。
 *
 * UI 调用点:
 *   - NetworkSettingsPage — Auto / Wifi / Cellular 三按钮
 */
interface NetworkActions {
    /**
     * 网络偏好变更(设置 → 网络子页)。
     * Android: SipViewModel 持久化 config + 调 NetworkController.apply(pref)
     *          → state flow 自动驱动 AppEngine.handleNetworkChange
     * iOS / JVM: no-op(子页入口已灰显拦截,这里兜底)
     */
    fun onNetworkPreferenceChange(preference: NetworkPreference)
}

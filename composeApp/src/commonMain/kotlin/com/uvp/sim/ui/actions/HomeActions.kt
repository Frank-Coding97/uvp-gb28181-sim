package com.uvp.sim.ui.actions

import com.uvp.sim.config.SimConfig

/**
 * 主屏会话动作 — slice 1/4(PR-B)。
 *
 * 范围:注册三件套(connect / cancelConnect / disconnect) + 会话级辅助动作
 * (配置保存 / 日志清除 / PTZ effect consume)。
 *
 * UI 调用点:
 *   - HomeScreen — 注册三件套 + ConfigSaveCard
 *   - LogScreen — 清日志
 *   - SettingsScreen / DeviceConfigScreen — onConfigSave
 *   - SimulateScreen — onConsumeDeviceEffect(LaunchedEffect 兜底)
 */
interface HomeActions {
    fun onConnect()
    fun onCancelConnect()
    fun onDisconnect()
    fun onConfigSave(updated: SimConfig)
    fun onClearSipLogs()
    fun onClearSystemLogs()

    /**
     * UI 层消费完 DeviceEffectDto 后调用,把 AppEngine 的 pendingEffect 置 null
     * 防止重复触发。SimulateScreen 在 LaunchedEffect(pendingEffect) 处理完动画/snackbar 后兜底调用。
     */
    fun onConsumeDeviceEffect()
}

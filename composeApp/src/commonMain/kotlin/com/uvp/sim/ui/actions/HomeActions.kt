package com.uvp.sim.ui.actions

import com.uvp.sim.config.QrFetchResult
import com.uvp.sim.config.SimConfig

/**
 * 主屏会话动作 — slice 1/4(PR-B)。
 *
 * 范围:注册三件套(connect / cancelConnect / disconnect) + 会话级辅助动作
 * (配置保存 / 日志清除 / PTZ effect consume / 扫码兑换)。
 *
 * UI 调用点:
 *   - HomeScreen — 注册三件套 + ConfigSaveCard
 *   - LogScreen — 清日志
 *   - SettingsScreen / DeviceConfigScreen — onConfigSave
 *   - SimulateScreen — onConsumeDeviceEffect(LaunchedEffect 兜底)
 *   - QrScanScreen — onQrExchange
 */
interface HomeActions {
    fun onConnect()
    fun onCancelConnect()
    fun onDisconnect()
    fun onConfigSave(updated: SimConfig)
    fun onClearSipLogs()
    fun onClearSystemLogs()

    /**
     * 扫码 token → SIP 六元组(plan §5.8)。
     *
     * HttpClient 由宿主(Android `SipViewModel` / iOS `IosAppHost`)持有并随其生命周期
     * 关闭 —— **不在 Composable 内建 client**,那样生命周期无人管、也没法 close。
     * 本方法是这条链路上唯一新增的 action。
     *
     * 参数拆成 baseUrl + token(不是原始扫码串):QrScanScreen 必须先自己解析,
     * 才能区分"不是 UVP 的码"并在确认页显示目标 host。
     */
    suspend fun onQrExchange(baseUrl: String, token: String): QrFetchResult

    /**
     * UI 层消费完 DeviceEffectDto 后调用,把 AppEngine 的 pendingEffect 置 null
     * 防止重复触发。SimulateScreen 在 LaunchedEffect(pendingEffect) 处理完动画/snackbar 后兜底调用。
     */
    fun onConsumeDeviceEffect()
}

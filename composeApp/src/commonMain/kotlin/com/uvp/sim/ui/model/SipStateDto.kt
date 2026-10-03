package com.uvp.sim.ui.model

/** UI 层 SIP 状态机 DTO. 1:1 映射 com.uvp.sim.sip.SipState. */
enum class SipStateDto {
    Disconnected,
    Registering,
    Registered,
    InCall,
    Failed,
}

/**
 * 是否持有**可注销的注册会话** —— 与 `com.uvp.sim.sip.hasActiveRegistration()` 同一口径。
 *
 * 扫码回填只在"没有活跃会话"时安全:`AppEngine.updateConfig` 对活跃会话会先发
 * `Expires=0` 注销再重建,而对 `Disconnected` / `Failed` 直接静默清场。UI 据此决定
 * 「扫一扫」放行还是拦截。
 *
 * ⛔ 别退回 `== Disconnected` 的单值判断 —— 那会把 `Failed` 一并拦掉,而 `Failed` 态下
 * 主屏按钮显示的是「注册」、没有「断开」可点,用户会被卡在一个无法执行的提示上
 * (2026-10-03 修复,见 `qrEntryBlockReason`)。
 */
fun SipStateDto.hasActiveRegistration(): Boolean = when (this) {
    SipStateDto.Registering, SipStateDto.Registered, SipStateDto.InCall -> true
    SipStateDto.Disconnected, SipStateDto.Failed -> false
}

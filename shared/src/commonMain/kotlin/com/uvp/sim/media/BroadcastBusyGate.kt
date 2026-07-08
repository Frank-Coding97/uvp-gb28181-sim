package com.uvp.sim.media

/**
 * 语音广播下行 busy 检测通用 gate(plan §3 模块 D · plan §5 Q4)。
 *
 * commonMain 抽象,`ManscdpRouterImpl` 走 Broadcast 分支前先问 [isBusy]:busy 则直接
 * 回 `Result=ERROR busy` 不发 INVITE(排队策略,plan §5 Q4 决策)。
 *
 * 平台 actual 实现:
 * - iOS:读 [com.uvp.sim.camera.IosAudioStreamer] 的 activeCount(录像 audio tap 计数)
 * - Android:常量 false(Android 现网 broadcast 与录像同 category 已跑通)
 * - JVM:常量 false(无冲突场景,仅 smoke)
 *
 * ⚠️ 注:老的 `broadcastBusy = { broadcast.current.value != null }` 是"防第二路 broadcast"
 * 保护,与本 gate 语义不冲突。本 gate 关注"录像 vs 广播"冲突,由 Router 分支叠加使用。
 */
expect object BroadcastBusyGate {
    fun isBusy(): Boolean
    fun busyReason(): String?
}

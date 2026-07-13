package com.uvp.sim.media

/**
 * 语音广播下行 busy 检测通用 gate(plan §3 模块 D · plan §5 Q4)。
 *
 * commonMain 抽象,`ManscdpRouterImpl` 走 Broadcast 分支前先问 [isBusy]:busy 则直接
 * 回 `Result=ERROR busy` 不发 INVITE(排队策略,plan §5 Q4 决策)。
 *
 * 平台 actual 实现:
 * - iOS / Android / JVM:当前均为常量 false。iOS 的采集和播放由共享 AVAudioSession 协调器处理。
 *
 * ⚠️ 注:老的 `broadcastBusy = { broadcast.current.value != null }` 是"防第二路 broadcast"
 * 保护,与本 gate 语义不冲突。本 gate 留作未来无法通过媒体协调器解决的物理资源冲突。
 */
expect object BroadcastBusyGate {
    fun isBusy(): Boolean
    fun busyReason(): String?
}

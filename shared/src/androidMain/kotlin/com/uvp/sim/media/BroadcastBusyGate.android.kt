package com.uvp.sim.media

/**
 * Android actual — 常量 false。
 *
 * Android/JVM 当前无冲突场景(Android 现网 broadcast 与录像同 category 已跑通),
 * 保持常量 false 语义。如后续接入需检查 AudioRecord 状态,请参考 iOS 的 activeCount 模式。
 */
actual object BroadcastBusyGate {
    actual fun isBusy(): Boolean = false
    actual fun busyReason(): String? = null
}

package com.uvp.sim.media

/**
 * Android actual — 常量 false。
 *
 * Android/JVM 当前无冲突场景(Android 现网 broadcast 与录像同 category 已跑通),
 * 保持常量 false 语义。
 */
actual object BroadcastBusyGate {
    actual fun isBusy(): Boolean = false
    actual fun busyReason(): String? = null
}

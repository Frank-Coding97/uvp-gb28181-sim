package com.uvp.sim.media

/**
 * JVM actual — 常量 false。
 *
 * JVM target 仅作 commonTest smoke,不接实际音频硬件,无冲突场景。
 */
actual object BroadcastBusyGate {
    actual fun isBusy(): Boolean = false
    actual fun busyReason(): String? = null
}

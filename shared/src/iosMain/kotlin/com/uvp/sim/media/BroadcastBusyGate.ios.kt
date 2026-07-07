package com.uvp.sim.media

/**
 * iOS actual — 语音广播 busy 检测。
 *
 * T-E2-0 骨架:先返回常量 false。
 * T-E2-2 将 [com.uvp.sim.camera.IosAudioStreamer.activeCount > 0] 接进来。
 */
actual object BroadcastBusyGate {
    actual fun isBusy(): Boolean = false
    actual fun busyReason(): String? = null
}

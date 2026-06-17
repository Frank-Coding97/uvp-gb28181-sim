package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * iOS stub(本轮只做安卓/JVM)。真实 BSDSocket / Network.framework 实现留后续。
 */
actual class RtpReceiver actual constructor(
    @Suppress("UNUSED_PARAMETER") parentScope: CoroutineScope?
) {
    actual val localPort: Int = -1

    actual suspend fun bind(mode: RtpMode): Int = -1

    actual suspend fun connect(remoteHost: String, remotePort: Int) {}

    actual fun start(onPacket: (RtpPacket) -> Unit): Job =
        Job().also { it.complete() }

    actual suspend fun close() {}
}

package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * iOS stub(M3 范围,plan Q6)。真实 BSDSocket / Network.framework 实现留 M4(T13)。
 * [SimulatorEngine] 检测 bindLocalPort 返回 -1 时不发 INVITE,回 Broadcast Response ERROR。
 */
actual class RtpReceiver actual constructor(
    @Suppress("UNUSED_PARAMETER") parentScope: CoroutineScope?
) {
    actual val localPort: Int = -1

    actual suspend fun bindLocalPort(): Int = -1

    actual fun start(onPacket: (RtpPacket) -> Unit): Job =
        Job().also { it.complete() }

    actual suspend fun close() {}
}

package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope

/**
 * iOS stub — real implementation lands in T13 alongside iOS camera capture.
 * For M1 Android-only, this stub keeps the expect/actual contract satisfied.
 */
actual class RtpSender actual constructor(
    actual val remoteHost: String,
    actual val remotePort: Int,
    @Suppress("UNUSED_PARAMETER") parentScope: CoroutineScope?
) {
    actual val localPort: Int = -1

    actual suspend fun bindLocalPort(): Int {
        error("RtpSender iOS stub — implemented in T13")
    }
    actual suspend fun send(packet: ByteArray) {
        error("RtpSender iOS stub — implemented in T13")
    }
    actual suspend fun close() {
        // no-op
    }
}

package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope

/**
 * UDP-only datagram sender for RTP streams.
 *
 * Distinct from [SipTransport]: no SIP parsing, no inbound flow — pure
 * unidirectional bytes-out. Each RTP packet is sent as one datagram.
 *
 * Lifecycle:
 *   1. `bindLocalPort()` — bind a local UDP socket. Returns the actual local
 *      port assigned by the OS. Idempotent.
 *   2. `send(packet)` — push one packet to the configured remote address.
 *   3. `close()` — release the socket.
 */
expect class RtpSender(
    remoteHost: String,
    remotePort: Int,
    parentScope: CoroutineScope? = null
) {
    val remoteHost: String
    val remotePort: Int
    /** Returns -1 until bindLocalPort() succeeds. */
    val localPort: Int

    suspend fun bindLocalPort(): Int
    suspend fun send(packet: ByteArray)
    suspend fun close()
}

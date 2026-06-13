package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope

/**
 * RTP transport mode for the sender side.
 *
 * - UDP: classic datagram per RTP packet (the GB28181 default)
 * - TCP_ACTIVE: device opens a TCP connection to the platform's listening port
 *   and prefixes each RTP packet with a 16-bit big-endian length (RFC 4571)
 * - TCP_PASSIVE: device listens; platform connects in. Same RFC 4571 framing.
 *
 * The mode is negotiated via SDP a=setup attribute: when the platform offers
 * `setup:passive`, the device answers `setup:active` and uses TCP_ACTIVE.
 */
enum class RtpMode { UDP, TCP_ACTIVE, TCP_PASSIVE }

/**
 * Bidirectional sender for the RTP media stream.
 *
 * Distinct from [SipTransport]: no SIP parsing, no inbound flow — pure
 * unidirectional bytes-out. Per [RtpMode] this may be UDP datagrams or a
 * TCP stream framed per RFC 4571.
 *
 * Lifecycle:
 *   1. `bindLocalPort()` — bind / connect a local socket. Returns the actual
 *      local port (UDP) or 0 (TCP_ACTIVE; the OS picks an ephemeral). Idempotent.
 *   2. `send(packet)` — push one RTP packet to the configured remote address.
 *   3. `close()` — release the socket.
 */
expect class RtpSender(
    remoteHost: String,
    remotePort: Int,
    parentScope: CoroutineScope? = null,
    mode: RtpMode = RtpMode.UDP
) {
    val remoteHost: String
    val remotePort: Int
    val mode: RtpMode
    /** Returns -1 until bindLocalPort() succeeds. */
    val localPort: Int

    suspend fun bindLocalPort(): Int
    suspend fun send(packet: ByteArray)
    suspend fun close()
}

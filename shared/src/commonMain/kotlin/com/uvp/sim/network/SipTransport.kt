package com.uvp.sim.network

import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the UDP/TCP transport used to send and receive SIP messages.
 *
 * Lifecycle:
 *   1. `connect()` — open the underlying socket. Idempotent.
 *   2. `incoming` — collect this Flow to receive parsed SIP messages.
 *   3. `send(...)` — emits a SIP message to the configured remote address.
 *   4. `close()` — release the socket. After close, [connect] may be called again.
 *
 * Implementations must be thread-safe / coroutine-safe — the simulator engine
 * may call `send` from any coroutine while `incoming` is being collected.
 */
interface SipTransport {
    suspend fun connect()
    suspend fun send(message: SipMessage)
    val incoming: Flow<SipMessage>
    suspend fun close()
}

/** Information about the remote SIP server. */
data class RemoteEndpoint(
    val host: String,
    val port: Int,
    val transport: TransportType
)

@kotlinx.serialization.Serializable
enum class TransportType { UDP, TCP }

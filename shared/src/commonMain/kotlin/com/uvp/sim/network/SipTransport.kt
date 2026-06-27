package com.uvp.sim.network

import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the UDP/TCP transport used to send and receive SIP messages.
 *
 * Lifecycle:
 *   1. `connect()` — open the underlying socket. Idempotent.
 *   2. `incoming` — collect this Flow to receive parsed SIP messages wrapped in [SipEnvelope].
 *   3. `send(...)` — emits a SIP message to the configured remote address.
 *   4. `close()` — release the socket. After close, [connect] may be called again.
 *
 * Implementations must be thread-safe / coroutine-safe — the simulator engine
 * may call `send` from any coroutine while `incoming` is being collected.
 *
 * **Wave 7B P0-1**: [incoming] 类型从 `Flow<SipMessage>` 改为 `Flow<SipEnvelope>`,
 * 携带网络层真实来源 IP/port,让上层 Coordinator 做基于 network 的来源校验。
 */
interface SipTransport {
    suspend fun connect()
    suspend fun send(message: SipMessage)
    val incoming: Flow<SipEnvelope>
    suspend fun close()

    /**
     * Local port the underlying socket is bound to.
     * Returns `-1` if not yet connected. Used by the engine to fill Via /
     * Contact headers so the platform can reach back at the right port.
     */
    val localPort: Int
}

/** Information about the remote SIP server. */
data class RemoteEndpoint(
    val host: String,
    val port: Int,
    val transport: TransportType,
    /**
     * M-6 (audit §3) — 服务器 IP 白名单。空 list = 不强制。transport.connect()
     * 时通过 [ServerAllowList.enforce] 校验,不命中拒绝 connect。
     */
    val allowList: List<String> = emptyList(),
)

@kotlinx.serialization.Serializable
enum class TransportType { UDP, TCP }

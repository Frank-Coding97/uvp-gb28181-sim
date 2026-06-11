package com.uvp.sim.network

import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipParseException
import com.uvp.sim.sip.SipParser
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.ByteReadPacket
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * UDP-based SIP transport (RFC 3261 § 18 baseline transport).
 *
 * Binds to a local address (or any-port if [localBindPort] = 0) and exchanges
 * datagrams with [remote]. Each incoming datagram is parsed as a single SIP
 * message — fragmented messages are not supported on UDP per RFC 3261 § 18.1.1.
 *
 * GB28181 platforms (WVP, EasyCVR, LiveGBS) all default to UDP transport.
 */
class UdpSipTransport(
    private val remote: RemoteEndpoint,
    private val localBindPort: Int = 0,
    private val parentScope: CoroutineScope? = null
) : SipTransport {

    private val mutex = Mutex()
    private var socket: BoundDatagramSocket? = null
    private var selector: SelectorManager? = null
    private var receiveJob: Job? = null
    private val ownedScope: CoroutineScope = parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _incoming = MutableSharedFlow<SipMessage>(extraBufferCapacity = 64)
    override val incoming: Flow<SipMessage> = _incoming.asSharedFlow()

    /** Public for testing / logs. The actual local port we ended up bound to. */
    val localPort: Int get() = (socket?.localAddress as? InetSocketAddress)?.port ?: -1

    override suspend fun connect(): Unit = mutex.withLock {
        if (socket != null) return
        val sm = SelectorManager(Dispatchers.Default)
        val sk = aSocket(sm)
            .udp()
            .bind(InetSocketAddress("0.0.0.0", localBindPort))
        selector = sm
        socket = sk
        receiveJob = ownedScope.launch {
            while (isActive) {
                val datagram = try {
                    sk.receive()
                } catch (e: Throwable) {
                    if (!isActive) break
                    continue  // Recover from spurious errors; final close() cancels job
                }
                val bytes = datagram.packet.readBytes()
                try {
                    val msg = SipParser.parse(bytes)
                    _incoming.emit(msg)
                } catch (e: SipParseException) {
                    // log and ignore malformed inbound (don't crash)
                    continue
                }
            }
        }
    }

    override suspend fun send(message: SipMessage) {
        val sk = socket ?: error("Transport not connected — call connect() first")
        val payload = message.toBytes()
        sk.send(
            Datagram(
                packet = ByteReadPacket(payload),
                address = InetSocketAddress(remote.host, remote.port)
            )
        )
    }

    override suspend fun close(): Unit = mutex.withLock {
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
        selector?.close()
        selector = null
        if (parentScope == null) {
            ownedScope.cancel()
        }
    }
}

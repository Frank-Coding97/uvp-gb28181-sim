package com.uvp.sim.network

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
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

    private val _incoming = MutableSharedFlow<SipEnvelope>(extraBufferCapacity = 64)
    override val incoming: Flow<SipEnvelope> = _incoming.asSharedFlow()

    /** Public for testing / logs. The actual local port we ended up bound to. */
    override val localPort: Int get() = (socket?.localAddress as? InetSocketAddress)?.port ?: -1

    override suspend fun connect(): Unit = mutex.withLock {
        if (socket != null) return
        // M-6 (audit §3) — 白名单非空时,目标 IP 必须命中。UDP 虽然不像 TCP 真 connect,
        // 但 send() 会发到 remote.host,这里在 bind 前就拒绝避免后续误发到非白名单 IP。
        ServerAllowList.enforce(remote.host, remote.allowList)
        val sm = SelectorManager(Dispatchers.Default)
        val sk = try {
            aSocket(sm)
                .udp()
                .bind(InetSocketAddress("0.0.0.0", localBindPort))
        } catch (e: Throwable) {
            sm.close()
            SystemLogger.emit(
                LogLevel.Error, LogTag.Network,
                "UDP bind 失败 :$localBindPort → ${e::class.simpleName}: ${e.message}"
            )
            throw e
        }
        selector = sm
        socket = sk
        val boundPort = (sk.localAddress as? InetSocketAddress)?.port ?: -1
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "UDP socket bound :$boundPort → ${remote.host}:${remote.port}"
        )
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
                    val sourceAddr = datagram.address as? InetSocketAddress
                    val envelope = SipEnvelope(
                        message = msg,
                        sourceIp = sourceAddr?.hostname ?: "0.0.0.0",
                        sourcePort = sourceAddr?.port ?: 0,
                        transport = TransportType.UDP,
                    )
                    _incoming.emit(envelope)
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
        try {
            sk.send(
                Datagram(
                    packet = ByteReadPacket(payload),
                    address = InetSocketAddress(remote.host, remote.port)
                )
            )
        } catch (e: Throwable) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Network,
                "UDP sendto ${remote.host}:${remote.port} 失败: ${e::class.simpleName}: ${e.message}"
            )
            throw e
        }
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

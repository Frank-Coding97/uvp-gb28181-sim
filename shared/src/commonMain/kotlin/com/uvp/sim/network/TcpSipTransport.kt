package com.uvp.sim.network

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipParseException
import com.uvp.sim.sip.SipParser
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeByteArray
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
 * TCP-based SIP transport (RFC 3261 § 18.2.2).
 *
 * Opens a persistent TCP connection to the remote SIP server. Incoming bytes
 * are framed by detecting SIP message boundaries via Content-Length header
 * (RFC 3261 § 7.5 — the only reliable framing method for SIP over TCP).
 *
 * GB28181 § 5.2 requires both UDP and TCP support. Some platforms (especially
 * behind NAT/firewall) prefer TCP for reliability.
 */
class TcpSipTransport(
    private val remote: RemoteEndpoint,
    private val parentScope: CoroutineScope? = null
) : SipTransport {

    private val mutex = Mutex()
    private var socket: Socket? = null
    private var selector: SelectorManager? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null
    private var receiveJob: Job? = null
    private val ownedScope: CoroutineScope = parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _incoming = MutableSharedFlow<SipMessage>(extraBufferCapacity = 64)
    override val incoming: Flow<SipMessage> = _incoming.asSharedFlow()

    override val localPort: Int
        get() = (socket?.localAddress as? InetSocketAddress)?.port ?: -1

    override suspend fun connect(): Unit = mutex.withLock {
        if (socket != null) return
        val sm = SelectorManager(Dispatchers.Default)
        val sk = try {
            aSocket(sm)
                .tcp()
                .connect(InetSocketAddress(remote.host, remote.port))
        } catch (e: Throwable) {
            sm.close()
            SystemLogger.emit(
                LogLevel.Error, LogTag.Network,
                "TCP connect ${remote.host}:${remote.port} 失败: ${e::class.simpleName}: ${e.message}"
            )
            throw e
        }
        selector = sm
        socket = sk
        readChannel = sk.openReadChannel()
        writeChannel = sk.openWriteChannel(autoFlush = true)
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "TCP connected → ${remote.host}:${remote.port}"
        )
        receiveJob = ownedScope.launch {
            val rc = readChannel ?: return@launch
            while (isActive) {
                try {
                    val msg = readSipMessage(rc) ?: break
                    _incoming.emit(msg)
                } catch (e: SipParseException) {
                    continue
                } catch (e: Throwable) {
                    if (!isActive) break
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Network,
                        "TCP read error: ${e::class.simpleName}: ${e.message}"
                    )
                    break
                }
            }
        }
    }

    override suspend fun send(message: SipMessage) {
        val wc = writeChannel ?: error("Transport not connected — call connect() first")
        val payload = message.toBytes()
        try {
            wc.writeByteArray(payload)
        } catch (e: Throwable) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Network,
                "TCP send 失败: ${e::class.simpleName}: ${e.message}"
            )
            throw e
        }
    }

    override suspend fun close(): Unit = mutex.withLock {
        receiveJob?.cancel()
        receiveJob = null
        readChannel = null
        writeChannel = null
        socket?.close()
        socket = null
        selector?.close()
        selector = null
        if (parentScope == null) {
            ownedScope.cancel()
        }
    }

    /**
     * Read a single SIP message from TCP stream using Content-Length framing.
     *
     * SIP over TCP: read headers line-by-line until blank line (CRLFCRLF),
     * extract Content-Length, then read exactly that many body bytes.
     */
    private suspend fun readSipMessage(channel: ByteReadChannel): SipMessage? {
        val headerLines = mutableListOf<String>()
        var contentLength = 0

        while (true) {
            val line = channel.readUTF8Line() ?: return null
            if (line.isEmpty()) break
            headerLines.add(line)
            val lower = line.lowercase()
            if (lower.startsWith("content-length:") || lower.startsWith("content-length :")) {
                contentLength = lower.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        if (headerLines.isEmpty()) return null

        val headerBytes = headerLines.joinToString("\r\n").toByteArray(Charsets.UTF_8)
        val separator = "\r\n\r\n".toByteArray(Charsets.UTF_8)

        val bodyBytes = if (contentLength > 0) {
            channel.readByteArray(contentLength)
        } else {
            ByteArray(0)
        }

        val full = headerBytes + separator + bodyBytes
        return SipParser.parse(full)
    }
}

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
import kotlinx.coroutines.withContext

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

    companion object {
        /**
         * H-2 (security-audit §2):TCP SIP 单条消息 body 上限 64 KiB。
         *
         * RFC 3261 § 18.1.1 建议 UDP MTU 1300,TCP 无硬上限但典型 SIP/MANSCDP /
         * SDP body 都在几 KB 内。攻击者可发 `Content-Length: 2147483647`
         * 让 [readByteArray] 一次性分配 2 GiB → OOM。这里设 64 KiB 上限。
         */
        internal const val MAX_SIP_BODY_BYTES: Int = 65_536

        /**
         * H-2:解析 `Content-Length:` 头一行,做合法性 + 上限校验。
         *
         * @return 解析后的字节数(0..MAX_SIP_BODY_BYTES)
         * @throws SipParseException 负数或超上限 → 视为攻击,关连接
         */
        internal fun parseContentLengthOrThrow(headerLine: String): Int {
            val cl = headerLine.lowercase().substringAfter(':').trim().toIntOrNull() ?: 0
            if (cl < 0) {
                throw SipParseException("Content-Length $cl is negative")
            }
            if (cl > MAX_SIP_BODY_BYTES) {
                throw SipParseException(
                    "Content-Length $cl exceeds max $MAX_SIP_BODY_BYTES — possible DoS"
                )
            }
            return cl
        }
    }

    override suspend fun connect(): Unit = mutex.withLock {
        if (socket != null) return
        // Android 主线程会触发 NetworkOnMainThreadException(ktor tcp().connect() 内部
        // 有同步 socket 检测,即便挂 suspend),切到 IO 线程跑。
        // ba7d597 已为 RTP TCP 修过同款问题,现在 SIP TCP 同步治理。
        withContext(Dispatchers.IO) {
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
    }

    override suspend fun send(message: SipMessage) {
        val wc = writeChannel ?: error("Transport not connected — call connect() first")
        val payload = message.toBytes()
        // write 在主线程也会撞 NetworkOnMainThreadException,统一在 IO 跑。
        withContext(Dispatchers.IO) {
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
    }

    override suspend fun close(): Unit = mutex.withLock {
        receiveJob?.cancel()
        receiveJob = null
        readChannel = null
        writeChannel = null
        // socket.close() / selector.close() 都涉及 fd 释放,在主线程上 strict
        // mode 同样会发火,统一到 IO。
        withContext(Dispatchers.IO) {
            socket?.close()
            socket = null
            selector?.close()
            selector = null
        }
        if (parentScope == null) {
            ownedScope.cancel()
        }
    }

    /**
     * Read a single SIP message from TCP stream using Content-Length framing.
     *
     * SIP over TCP: read headers line-by-line until blank line (CRLFCRLF),
     * extract Content-Length, then read exactly that many body bytes.
     *
     * H-2:Content-Length 上限 [MAX_SIP_BODY_BYTES],超过则抛 [SipParseException]
     * 触发外层 break(连接关闭),避免攻击者用畸形 CL 触发 OOM。
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
                contentLength = parseContentLengthOrThrow(line)
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

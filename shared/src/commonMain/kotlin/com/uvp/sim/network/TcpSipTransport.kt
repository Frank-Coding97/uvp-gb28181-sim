package com.uvp.sim.network

import com.uvp.sim.concurrency.IoDispatcher
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

    private val _incoming = MutableSharedFlow<SipEnvelope>(extraBufferCapacity = 64)
    override val incoming: Flow<SipEnvelope> = _incoming.asSharedFlow()

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
            val raw = headerLine.lowercase().substringAfter(':').trim()
            // cross-review R1 #1:非数字 Content-Length 过去 `toIntOrNull() ?: 0` 静默当 0,
            // body 不被读取 → TCP SIP 流错位(下一 read 把 payload 当新 start-line)。
            // fail-closed:头存在但值非合法整数,视为攻击/损坏帧,抛异常关连接。
            val cl = raw.toIntOrNull()
                ?: throw SipParseException("Content-Length \"$raw\" is not a valid integer")
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

        /**
         * M-3 (audit §3) — TCP SIP socket option 配置。
         *
         * 抽成静态函数方便单测注入 stub 验证 keepAlive 被打开。
         * NAT 中间设备(家用路由 / 运营商 CGN)对 TCP idle 通常 5-30 分钟回收,
         * SIP TCP 长连接不开 keepalive 在 NAT 后会变成单边死连(本地认为连着,
         * 中间设备已断,平台再回包打不通)。
         *
         * ktor 跨平台 [SocketOptions.TCPClientSocketOptions.keepAlive] 在 JVM /
         * Native / iOS Network.framework 都生效;TCP_KEEPIDLE/INTERVAL 仅 JVM
         * Linux 内核可调(JDK 11+ JEP 350),其它平台用内核默认值(macOS 2h,
         * Linux 7200s)。SIP 平台多有应用层 keepalive 心跳(GB28181 默认 60s),
         * 内核 keepalive 主要兜底应用层心跳被卡死。
         *
         * 注:ktor 3.0.2 的 [SocketOptions.TCPClientSocketOptions] 构造 `internal`,
         * 无法在测试里直接构造,所以测试通过 [keepAliveSetting] 标志位 + lambda
         * 间接验证(单测注入 stub setter,断言被调用)。
         */
        internal const val keepAliveSetting: Boolean = true

        internal fun configureKeepAlive(setKeepAlive: (Boolean) -> Unit) {
            setKeepAlive(keepAliveSetting)
        }
    }

    override suspend fun connect(): Unit = mutex.withLock {
        if (socket != null) return
        // M-6 (audit §3) — 配置白名单不空时,目标 IP 必须命中。
        ServerAllowList.enforce(remote.host, remote.allowList)
        // Android 主线程会触发 NetworkOnMainThreadException(ktor tcp().connect() 内部
        // 有同步 socket 检测,即便挂 suspend),切到 IO 线程跑。
        // ba7d597 已为 RTP TCP 修过同款问题,现在 SIP TCP 同步治理。
        withContext(IoDispatcher) {
            val sm = SelectorManager(Dispatchers.Default)
            val sk = try {
                aSocket(sm)
                    .tcp()
                    .connect(InetSocketAddress(remote.host, remote.port)) {
                        configureKeepAlive { keepAlive = it }
                    }
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
                "TCP connected → ${remote.host}:${remote.port} keepalive=on"
            )
            receiveJob = ownedScope.launch {
                val rc = readChannel ?: return@launch
                // TCP 单连接,sourceIp/Port 在 connect() 后就确定,read loop 复用 remote endpoint。
                val remoteAddr = sk.remoteAddress as? InetSocketAddress
                val sourceIp = remoteAddr?.hostname ?: remote.host
                val sourcePort = remoteAddr?.port ?: remote.port
                while (isActive) {
                    try {
                        val msg = readSipMessage(rc) ?: break
                        _incoming.emit(
                            SipEnvelope(
                                message = msg,
                                sourceIp = sourceIp,
                                sourcePort = sourcePort,
                                transport = TransportType.TCP,
                            )
                        )
                    } catch (e: SipParseException) {
                        // R1 #8:帧级解析错误(畸形 Content-Length / 越界 / Header 截断)
                        // 不能 continue —— 后续字节流已经无法对齐到合法帧边界,继续读
                        // 只会循环消费垃圾数据。直接 break read-loop 让连接重建。
                        SystemLogger.emit(
                            LogLevel.Warning, LogTag.Network,
                            "TCP SIP framing error, dropping connection: ${e.message}"
                        )
                        break
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
        withContext(IoDispatcher) {
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
        withContext(IoDispatcher) {
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

        val headerBytes = headerLines.joinToString("\r\n").encodeToByteArray()
        val separator = "\r\n\r\n".encodeToByteArray()

        val bodyBytes = if (contentLength > 0) {
            channel.readByteArray(contentLength)
        } else {
            ByteArray(0)
        }

        val full = headerBytes + separator + bodyBytes
        return SipParser.parse(full)
    }
}

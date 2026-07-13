package com.uvp.sim.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * iOS 实现 — Ktor Native sockets(UDP + TCP),对齐 JVM/Android 语义。
 *
 * 3 种模式全实:
 *   - UDP:`aSocket(sm).udp().bind(...)`,receive() 拿 Datagram → readBytes 提 payload
 *   - TCP_PASSIVE:`.tcp().bind(...)` 拿 ServerSocket,start() 内 accept 单连接
 *   - TCP_ACTIVE:`.tcp().connect(...)` 主动连平台
 *
 * TCP 一律走 RFC 4571:每 RTP 前 2 字节大端长度。
 *
 * M-1 (audit §3) 源验证 [RtpSourceGuard]:
 *   - UDP 每包比对 datagram.address 的 hostname 与 [expectedSourceHost];
 *   - TCP 因为 connect/accept 已经把对端绑死,只需校验 SSRC 锁。
 *
 * scope 管理对齐 iosMain sender:parentScope 为 null 则起 owned SupervisorJob,close 时 cancel。
 */
actual class RtpReceiver actual constructor(
    private val parentScope: CoroutineScope?,
    private val expectedSourceHost: String?,
) {
    private var mode: RtpMode = RtpMode.UDP
    private var selector: SelectorManager? = null
    private var udpSocket: BoundDatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private var tcpSocket: Socket? = null
    private val guard = RtpSourceGuard(expectedSourceHost)

    private val ownedScope: CoroutineScope = parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    actual val localPort: Int
        get() = when (mode) {
            RtpMode.UDP -> (udpSocket?.localAddress as? InetSocketAddress)?.port ?: -1
            RtpMode.TCP_PASSIVE -> (tcpServer?.localAddress as? InetSocketAddress)?.port ?: -1
            RtpMode.TCP_ACTIVE -> (tcpSocket?.localAddress as? InetSocketAddress)?.port ?: 0
        }

    actual suspend fun bind(mode: RtpMode): Int {
        this.mode = mode
        val sm = SelectorManager(Dispatchers.Default)
        selector = sm
        return when (mode) {
            RtpMode.UDP -> {
                val sk = aSocket(sm).udp().bind(InetSocketAddress("0.0.0.0", 0))
                udpSocket = sk
                (sk.localAddress as? InetSocketAddress)?.port ?: -1
            }
            RtpMode.TCP_PASSIVE -> {
                val server = aSocket(sm).tcp().bind(InetSocketAddress("0.0.0.0", 0))
                tcpServer = server
                (server.localAddress as? InetSocketAddress)?.port ?: -1
            }
            RtpMode.TCP_ACTIVE -> 0 // 不监听,offer SDP port=0,connect() 时再建连
        }
    }

    actual suspend fun connect(remoteHost: String, remotePort: Int) {
        if (mode != RtpMode.TCP_ACTIVE) return
        val sm = selector ?: SelectorManager(Dispatchers.Default).also { selector = it }
        tcpSocket = aSocket(sm).tcp().connect(InetSocketAddress(remoteHost, remotePort))
    }

    actual fun start(onPacket: (RtpPacket) -> Unit): Job =
        ownedScope.launch(Dispatchers.Default) {
            when (mode) {
                RtpMode.UDP -> udpLoop(onPacket)
                RtpMode.TCP_ACTIVE -> {
                    val sock = tcpSocket ?: error("call connect() first for TCP_ACTIVE")
                    tcpReadLoop(sock, onPacket)
                }
                RtpMode.TCP_PASSIVE -> {
                    val server = tcpServer ?: error("call bind() first for TCP_PASSIVE")
                    val client = try {
                        server.accept()
                    } catch (_: CancellationException) {
                        throw CancellationException()
                    } catch (_: Throwable) {
                        return@launch // socket 已 close,退出
                    }
                    tcpSocket = client
                    tcpReadLoop(client, onPacket)
                }
            }
        }

    private suspend fun CoroutineScope.udpLoop(onPacket: (RtpPacket) -> Unit) {
        val sock = udpSocket ?: error("call bind() first for UDP")
        while (isActive) {
            try {
                val datagram = sock.receive()
                val bytes = datagram.packet.readBytes()
                val rtp = RtpPacket.parse(bytes) ?: continue
                val srcHost = (datagram.address as? InetSocketAddress)?.hostname
                if (!guard.accept(rtp, srcHost)) continue
                onPacket(rtp)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Throwable) {
                // socket 关 / IO 中断 / 解包异常 — 退出循环
                break
            }
        }
    }

    /** RFC 4571:每包前缀 2 字节大端长度。channel 关闭时 readByteArray 抛异常触发退出。 */
    private suspend fun CoroutineScope.tcpReadLoop(sock: Socket, onPacket: (RtpPacket) -> Unit) {
        val srcHost = (sock.remoteAddress as? InetSocketAddress)?.hostname
        val channel: ByteReadChannel = sock.openReadChannel()
        try {
            while (isActive) {
                val lenBuf = channel.readByteArray(2)
                if (lenBuf.size < 2) break
                val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                if (len <= 0 || len > 65535) break
                val frame = channel.readByteArray(len)
                if (frame.size < len) break
                val rtp = RtpPacket.parse(frame) ?: continue
                if (!guard.accept(rtp, srcHost)) continue
                onPacket(rtp)
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: Throwable) {
            // EOF / 流关闭 / 半包 — 静默退出,close() 之后正常路径
        }
    }

    actual suspend fun close() {
        runCatching { udpSocket?.close() }; udpSocket = null
        runCatching { tcpSocket?.close() }; tcpSocket = null
        runCatching { tcpServer?.close() }; tcpServer = null
        runCatching { selector?.close() }; selector = null
        if (parentScope == null) {
            ownedScope.cancel()
        }
    }
}

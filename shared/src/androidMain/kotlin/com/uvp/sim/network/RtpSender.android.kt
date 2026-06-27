package com.uvp.sim.network

import com.uvp.sim.observability.ErrorCategory
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.ByteReadPacket
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android implementation. Same code as JVM since both use Ktor on java.nio.
 *
 * Supports UDP (default) and TCP via RFC 4571 length-prefixed framing
 * (TCP_ACTIVE = device connects out, TCP_PASSIVE = device listens).
 *
 * P1-5 (audit §2) TCP_PASSIVE accept guard:
 *   - [expectedClientHost] 非 null 时,只接受该 IP 连接,其它连接 close + 继续等,
 *     多次失败(MAX_ACCEPT_MISMATCH)后放弃。
 */
actual class RtpSender actual constructor(
    actual val remoteHost: String,
    actual val remotePort: Int,
    private val parentScope: CoroutineScope?,
    actual val mode: RtpMode,
    private val expectedClientHost: String?
) {
    private val mutex = Mutex()
    private var udpSocket: BoundDatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var tcpServer: ServerSocket? = null
    private var tcpWrite: ByteWriteChannel? = null
    private var selector: SelectorManager? = null
    private val ownedScope: CoroutineScope = parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    actual val localPort: Int
        get() = when (mode) {
            RtpMode.UDP -> (udpSocket?.localAddress as? InetSocketAddress)?.port ?: -1
            RtpMode.TCP_ACTIVE -> (tcpSocket?.localAddress as? InetSocketAddress)?.port ?: -1
            RtpMode.TCP_PASSIVE -> (tcpServer?.localAddress as? InetSocketAddress)?.port ?: -1
        }

    actual suspend fun bindLocalPort(): Int = mutex.withLock {
        when (mode) {
            RtpMode.UDP -> bindUdp()
            // TCP 在 Android 主线程会触发 NetworkOnMainThreadException(connect/bind 内部
            // 有同步检测),切到 IO 线程跑。UDP 走 NIO 不需要切,保持原行为避免回归。
            RtpMode.TCP_ACTIVE -> withContext(Dispatchers.IO) { bindTcpActive() }
            RtpMode.TCP_PASSIVE -> withContext(Dispatchers.IO) { bindTcpPassive() }
        }
    }

    private suspend fun bindUdp(): Int {
        if (udpSocket != null) return localPort
        val sm = SelectorManager(Dispatchers.Default)
        val sk = aSocket(sm).udp().bind(InetSocketAddress("0.0.0.0", 0))
        selector = sm
        udpSocket = sk
        return localPort
    }

    private suspend fun bindTcpActive(): Int {
        if (tcpSocket != null) return localPort
        val sm = SelectorManager(Dispatchers.Default)
        val sk = aSocket(sm).tcp().connect(InetSocketAddress(remoteHost, remotePort))
        selector = sm
        tcpSocket = sk
        tcpWrite = sk.openWriteChannel(autoFlush = true)
        return localPort
    }

    private suspend fun bindTcpPassive(): Int {
        if (tcpServer != null) return localPort
        val sm = SelectorManager(Dispatchers.Default)
        val server = aSocket(sm).tcp().bind(InetSocketAddress("0.0.0.0", 0))
        selector = sm
        tcpServer = server
        ownedScope.launch(Dispatchers.IO) {
            var mismatchCount = 0
            while (mismatchCount < MAX_ACCEPT_MISMATCH) {
                try {
                    val client = server.accept()
                    val clientHost = (client.remoteAddress as? InetSocketAddress)?.hostname

                    if (expectedClientHost != null && clientHost != null && clientHost != expectedClientHost) {
                        mismatchCount++
                        SystemLogger.emit(
                            LogLevel.Warning, LogTag.Network,
                            "TCP_PASSIVE accepted from unexpected $clientHost, expected $expectedClientHost — dropped (attempt $mismatchCount/$MAX_ACCEPT_MISMATCH)",
                            category = ErrorCategory.ProtocolViolation
                        )
                        runCatching { client.close() }
                        continue  // 继续等下一个连接
                    }

                    // 匹配或 expectedClientHost == null,接受连接
                    tcpSocket = client
                    tcpWrite = client.openWriteChannel(autoFlush = true)
                    break
                } catch (_: Throwable) {
                    /* shutdown path or accept error */
                    break
                }
            }
            if (mismatchCount >= MAX_ACCEPT_MISMATCH) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Network,
                    "TCP_PASSIVE give up after $MAX_ACCEPT_MISMATCH mismatched connection attempts",
                    category = ErrorCategory.ProtocolViolation
                )
            }
        }
        return localPort
    }

    companion object {
        private const val MAX_ACCEPT_MISMATCH = 10
    }

    actual suspend fun send(packet: ByteArray) {
        when (mode) {
            RtpMode.UDP -> {
                val sk = udpSocket ?: error("UDP RtpSender not bound — call bindLocalPort() first")
                sk.send(
                    Datagram(
                        packet = ByteReadPacket(packet),
                        address = InetSocketAddress(remoteHost, remotePort)
                    )
                )
            }
            RtpMode.TCP_ACTIVE, RtpMode.TCP_PASSIVE -> withContext(Dispatchers.IO) {
                val wc = tcpWrite ?: return@withContext
                val len = packet.size
                val framed = ByteArray(2 + len)
                framed[0] = ((len ushr 8) and 0xFF).toByte()
                framed[1] = (len and 0xFF).toByte()
                packet.copyInto(framed, 2)
                wc.writeByteArray(framed)
            }
        }
    }

    actual suspend fun close() = mutex.withLock {
        runCatching { tcpWrite = null }
        runCatching { tcpSocket?.close(); tcpSocket = null }
        runCatching { tcpServer?.close(); tcpServer = null }
        runCatching { udpSocket?.close(); udpSocket = null }
        runCatching { selector?.close(); selector = null }
        if (parentScope == null) {
            ownedScope.cancel()
        }
    }
}

package com.uvp.sim.network

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

/**
 * iOS implementation. Same shape as JVM/Android since Ktor Native sockets
 * cover UDP and TCP on iOS arm64 / x64 / simulator-arm64.
 */
actual class RtpSender actual constructor(
    actual val remoteHost: String,
    actual val remotePort: Int,
    private val parentScope: CoroutineScope?,
    actual val mode: RtpMode
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
            RtpMode.TCP_ACTIVE -> bindTcpActive()
            RtpMode.TCP_PASSIVE -> bindTcpPassive()
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
        ownedScope.launch {
            try {
                val client = server.accept()
                tcpSocket = client
                tcpWrite = client.openWriteChannel(autoFlush = true)
            } catch (_: Throwable) { /* shutdown path */ }
        }
        return localPort
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
            RtpMode.TCP_ACTIVE, RtpMode.TCP_PASSIVE -> {
                val wc = tcpWrite ?: return
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

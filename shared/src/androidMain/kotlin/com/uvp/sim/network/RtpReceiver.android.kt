package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Android 实现 — java.net.DatagramSocket(与 JVM 实现共享逻辑,Android 即 JVM 运行时)。
 */
actual class RtpReceiver actual constructor(
    private val parentScope: CoroutineScope?
) {
    private var socket: DatagramSocket? = null
    private val mtu = 1500

    actual val localPort: Int
        get() = socket?.localPort ?: -1

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private val scope: CoroutineScope get() = parentScope ?: GlobalScope

    actual suspend fun bindLocalPort(): Int = withContext(Dispatchers.IO) {
        val sk = DatagramSocket(0).apply { reuseAddress = true; soTimeout = 200 }
        socket = sk
        sk.localPort
    }

    actual fun start(onPacket: (RtpPacket) -> Unit): Job =
        scope.launch(Dispatchers.IO) {
            val sock = socket ?: throw IllegalStateException("call bindLocalPort() first")
            val buf = ByteArray(mtu)
            val pkt = DatagramPacket(buf, mtu)
            while (isActive) {
                try {
                    sock.receive(pkt)
                    val bytes = buf.copyOf(pkt.length)
                    val rtp = RtpPacket.parse(bytes) ?: continue
                    onPacket(rtp)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: SocketException) {
                    break
                }
            }
        }

    actual suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
        }
    }
}

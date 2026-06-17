package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * JVM 实现 — java.net,支持 UDP / TCP 主动 / TCP 被动。
 * TCP 走 RFC 4571 解帧(2 字节大端长度 + RTP 包)。
 */
actual class RtpReceiver actual constructor(
    private val parentScope: CoroutineScope?
) {
    private var mode: RtpMode = RtpMode.UDP
    private var udp: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var tcpSocket: Socket? = null
    private val mtu = 1500

    actual val localPort: Int
        get() = when (mode) {
            RtpMode.UDP -> udp?.localPort ?: -1
            RtpMode.TCP_PASSIVE -> serverSocket?.localPort ?: -1
            RtpMode.TCP_ACTIVE -> tcpSocket?.localPort ?: 0
        }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private val scope: CoroutineScope get() = parentScope ?: GlobalScope

    actual suspend fun bind(mode: RtpMode): Int = withContext(Dispatchers.IO) {
        this@RtpReceiver.mode = mode
        when (mode) {
            RtpMode.UDP -> {
                val sk = DatagramSocket(0).apply { reuseAddress = true; soTimeout = 200 }
                udp = sk
                sk.localPort
            }
            RtpMode.TCP_PASSIVE -> {
                val ss = ServerSocket(0)
                serverSocket = ss
                ss.localPort
            }
            RtpMode.TCP_ACTIVE -> 0  // 不监听,offer port 填 0,connect() 时再建连
        }
    }

    actual suspend fun connect(remoteHost: String, remotePort: Int) {
        if (mode != RtpMode.TCP_ACTIVE) return
        withContext(Dispatchers.IO) {
            tcpSocket = Socket(remoteHost, remotePort)
        }
    }

    actual fun start(onPacket: (RtpPacket) -> Unit): Job =
        scope.launch(Dispatchers.IO) {
            when (mode) {
                RtpMode.UDP -> udpLoop(onPacket)
                RtpMode.TCP_ACTIVE -> {
                    val sock = tcpSocket ?: throw IllegalStateException("call connect() first")
                    tcpReadLoop(sock, onPacket)
                }
                RtpMode.TCP_PASSIVE -> {
                    val client = serverSocket?.accept() ?: return@launch  // 阻塞等平台连入
                    tcpSocket = client
                    tcpReadLoop(client, onPacket)
                }
            }
        }

    private fun CoroutineScope.udpLoop(onPacket: (RtpPacket) -> Unit) {
        val sock = udp ?: throw IllegalStateException("call bind() first")
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

    /** RFC 4571:每个 RTP 包前缀 2 字节大端长度。close() 关 socket 时 read 抛异常退出。 */
    private fun CoroutineScope.tcpReadLoop(sock: Socket, onPacket: (RtpPacket) -> Unit) {
        try {
            val ins = sock.getInputStream()
            val lenBuf = ByteArray(2)
            while (isActive) {
                if (!readFully(ins, lenBuf, 2)) break
                val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                if (len <= 0 || len > 65535) break
                val frame = ByteArray(len)
                if (!readFully(ins, frame, len)) break
                val rtp = RtpPacket.parse(frame) ?: continue
                onPacket(rtp)
            }
        } catch (_: SocketException) {
            // close() 关 socket 触发,正常退出
        } catch (_: Throwable) {
            // 流结束 / 其它 IO 错误,退出循环
        }
    }

    private fun readFully(ins: InputStream, buf: ByteArray, n: Int): Boolean {
        var off = 0
        while (off < n) {
            val r = ins.read(buf, off, n - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    actual suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { udp?.close() }; udp = null
            runCatching { tcpSocket?.close() }; tcpSocket = null
            runCatching { serverSocket?.close() }; serverSocket = null
        }
    }
}

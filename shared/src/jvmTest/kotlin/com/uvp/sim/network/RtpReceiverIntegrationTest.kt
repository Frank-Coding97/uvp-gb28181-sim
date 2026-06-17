package com.uvp.sim.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T5 — RtpReceiver JVM 实现集成测(真 socket,runBlocking 走真实时间):UDP + TCP 主动 + TCP 被动。 */
class RtpReceiverIntegrationTest {

    private fun minimalRtp(seq: Int, payloadType: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte()
        h[1] = (payloadType and 0x7F).toByte()
        h[2] = ((seq ushr 8) and 0xFF).toByte()
        h[3] = (seq and 0xFF).toByte()
        return h + payload
    }

    /** RFC 4571:2 字节大端长度前缀 + RTP 包。 */
    private fun framed(rtp: ByteArray): ByteArray {
        val out = ByteArray(2 + rtp.size)
        out[0] = ((rtp.size ushr 8) and 0xFF).toByte()
        out[1] = (rtp.size and 0xFF).toByte()
        rtp.copyInto(out, 2)
        return out
    }

    // ---------- UDP ----------

    @Test
    fun udpBindReturnsNonZero() = runBlocking {
        val rx = RtpReceiver(this)
        val port = rx.bind(RtpMode.UDP)
        assertTrue(port in 1..65535, "应拿到合法本地端口,实际 $port")
        rx.close()
    }

    @Test
    fun udpReceivesPacket() = runBlocking {
        val rx = RtpReceiver(this)
        val port = rx.bind(RtpMode.UDP)
        val received = CompletableDeferred<RtpPacket>()
        val job = rx.start { received.complete(it) }

        val sender = DatagramSocket()
        val bytes = minimalRtp(1, 8, "hi".encodeToByteArray())
        sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port))
        sender.close()

        val rtp = withTimeout(2000) { received.await() }
        assertEquals(8, rtp.payloadType)
        assertEquals("hi", rtp.payload.decodeToString())
        rx.close()
        job.cancelAndJoin()
    }

    // ---------- TCP 主动(设备连平台,主力)----------

    @Test
    fun tcpActiveConnectsAndReceivesFramed() = runBlocking {
        // 假平台:本地起 ServerSocket 监听
        val platform = ServerSocket(0)
        val platformPort = platform.localPort

        val rx = RtpReceiver(this)
        val bound = rx.bind(RtpMode.TCP_ACTIVE)
        assertEquals(0, bound, "TCP 主动不监听,bind 返回 0")

        rx.connect("127.0.0.1", platformPort)  // 设备主动连
        val received = CompletableDeferred<RtpPacket>()
        val job = rx.start { received.complete(it) }

        // 平台 accept 后往 TCP 通道下发 RFC 4571 帧
        val client = platform.accept()
        val rtp = minimalRtp(7, 8, "tcp".encodeToByteArray())
        client.getOutputStream().write(framed(rtp))
        client.getOutputStream().flush()

        val got = withTimeout(2000) { received.await() }
        assertEquals(8, got.payloadType)
        assertEquals("tcp", got.payload.decodeToString())
        rx.close()
        job.cancelAndJoin()
        client.close()
        platform.close()
    }

    // ---------- TCP 被动(设备监听,平台连进来)----------

    @Test
    fun tcpPassiveAcceptsAndReceivesFramed() = runBlocking {
        val rx = RtpReceiver(this)
        val port = rx.bind(RtpMode.TCP_PASSIVE)
        assertTrue(port in 1..65535, "TCP 被动监听本地端口,实际 $port")

        val received = CompletableDeferred<RtpPacket>()
        val job = rx.start { received.complete(it) }

        // 平台主动连进来并下发帧
        val platform = Socket("127.0.0.1", port)
        val rtp = minimalRtp(9, 0, "pas".encodeToByteArray())
        platform.getOutputStream().write(framed(rtp))
        platform.getOutputStream().flush()

        val got = withTimeout(2000) { received.await() }
        assertEquals(0, got.payloadType)
        assertEquals("pas", got.payload.decodeToString())
        rx.close()
        job.cancelAndJoin()
        platform.close()
    }
}

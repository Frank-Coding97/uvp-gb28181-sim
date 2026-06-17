package com.uvp.sim.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T5 — RtpReceiver JVM 实现集成测(真 UDP socket,用 runBlocking 走真实时间)。 */
class RtpReceiverIntegrationTest {

    private fun minimalRtp(seq: Int, payloadType: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte()                       // version=2
        h[1] = (payloadType and 0x7F).toByte()
        h[2] = ((seq ushr 8) and 0xFF).toByte()
        h[3] = (seq and 0xFF).toByte()
        return h + payload
    }

    @Test
    fun bindLocalPortReturnsNonZero() = runBlocking {
        val rx = RtpReceiver(this)
        val port = rx.bindLocalPort()
        assertTrue(port in 1..65535, "应拿到合法本地端口,实际 $port")
        rx.close()
    }

    @Test
    fun startReceivesUdpPacket() = runBlocking {
        val rx = RtpReceiver(this)
        val port = rx.bindLocalPort()
        val received = CompletableDeferred<RtpPacket>()
        val job = rx.start { received.complete(it) }

        val sender = DatagramSocket()
        val bytes = minimalRtp(seq = 1, payloadType = 8, payload = "hi".encodeToByteArray())
        sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port))
        sender.close()

        val rtp = withTimeout(2000) { received.await() }
        assertEquals(8, rtp.payloadType)
        assertEquals("hi", rtp.payload.decodeToString())
        job.cancelAndJoin()
        rx.close()
    }

    @Test
    fun closeReleasesSocket() = runBlocking {
        val rx = RtpReceiver(this)
        val port = rx.bindLocalPort()
        rx.close()
        // 端口已释放,可再绑同一端口
        val sock = DatagramSocket(port)
        sock.close()
    }
}

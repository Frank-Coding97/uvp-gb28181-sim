package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RtpSenderTest {

    private val mockReceiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest fun tearDown() {
        mockReceiver.close()
    }

    @Test fun bindReturnsLocalPort() = runBlocking {
        val sender = RtpSender("127.0.0.1", mockReceiver.localPort, scope)
        try {
            val port = sender.bindLocalPort()
            assertTrue(port > 0)
            assertEquals(port, sender.localPort)
        } finally {
            sender.close()
        }
    }

    @Test fun sendDeliversBytesToRemote() = runBlocking {
        val sender = RtpSender("127.0.0.1", mockReceiver.localPort, scope)
        sender.bindLocalPort()
        val rtpPayload = ByteArray(200) { (it and 0xFF).toByte() }
        sender.send(rtpPayload)

        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        mockReceiver.soTimeout = 5000
        mockReceiver.receive(pkt)
        val received = buf.copyOfRange(0, pkt.length)
        assertTrue(received.contentEquals(rtpPayload))
        sender.close()
    }

    @Test fun multiplePacketsArriveInOrder() = runBlocking {
        val sender = RtpSender("127.0.0.1", mockReceiver.localPort, scope)
        sender.bindLocalPort()
        val packets = (1..5).map { i -> ByteArray(50) { i.toByte() } }
        for (p in packets) sender.send(p)
        mockReceiver.soTimeout = 5000

        for (expected in packets) {
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            mockReceiver.receive(pkt)
            val got = buf.copyOfRange(0, pkt.length)
            assertTrue(got.contentEquals(expected))
        }
        sender.close()
    }

    @Test fun closeReleasesPort() = runBlocking {
        val sender = RtpSender("127.0.0.1", mockReceiver.localPort, scope)
        sender.bindLocalPort()
        val first = sender.localPort
        sender.close()
        // After close, can bind again on potentially different port
        sender.bindLocalPort()
        assertTrue(sender.localPort > 0)
        sender.close()
    }

    @Test fun bindIsIdempotent() = runBlocking {
        val sender = RtpSender("127.0.0.1", mockReceiver.localPort, scope)
        val p1 = sender.bindLocalPort()
        val p2 = sender.bindLocalPort()
        assertEquals(p1, p2)
        sender.close()
    }
}

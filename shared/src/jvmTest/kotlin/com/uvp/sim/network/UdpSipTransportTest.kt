package com.uvp.sim.network

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipParser
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM-only integration tests for [UdpSipTransport]. The real Ktor UDP socket is
 * paired with a tiny java.net DatagramSocket "server" that echoes well-formed
 * SIP responses back to the client.
 *
 * These tests would also pass on Android (where Ktor sockets are JVM-backed),
 * but cannot run on Kotlin Native iOS — a separate iOS-only smoke test would
 * use platform sockets if needed.
 */
class UdpSipTransportTest {

    private val mockServer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        mockServer.close()
    }

    @Test fun sendDeliversBytesToRemote() = runBlocking {
        val transport = UdpSipTransport(
            remote = RemoteEndpoint("127.0.0.1", mockServer.localPort, TransportType.UDP),
            parentScope = scope
        )
        transport.connect()

        val request = SipRequest(
            method = SipMethod.REGISTER,
            requestUri = "sip:test@example.com",
            headers = listOf(
                SipMessage.Header("Via", "SIP/2.0/UDP 127.0.0.1:0"),
                SipMessage.Header("Call-ID", "udp-test"),
                SipMessage.Header("CSeq", "1 REGISTER"),
                SipMessage.Header("From", "<sip:u@e>;tag=t"),
                SipMessage.Header("To", "<sip:u@e>")
            )
        )
        transport.send(request)

        // Receive on mock server
        val buf = ByteArray(8192)
        val pkt = DatagramPacket(buf, buf.size)
        mockServer.soTimeout = 5000
        mockServer.receive(pkt)
        val received = SipParser.parse(buf.copyOf(pkt.length))
        assertTrue(received is SipRequest)
        assertEquals(SipMethod.REGISTER, received.method)
        assertEquals("udp-test", received.callId())

        transport.close()
    }

    @Test fun incomingFlowEmitsParsedResponse() = runBlocking {
        val transport = UdpSipTransport(
            remote = RemoteEndpoint("127.0.0.1", mockServer.localPort, TransportType.UDP),
            parentScope = scope
        )
        transport.connect()

        // Send a probe so the server learns our address
        val probe = SipRequest(
            method = SipMethod.OPTIONS,
            requestUri = "sip:test@example.com",
            headers = listOf(
                SipMessage.Header("Via", "SIP/2.0/UDP 127.0.0.1:0"),
                SipMessage.Header("Call-ID", "probe"),
                SipMessage.Header("CSeq", "1 OPTIONS"),
                SipMessage.Header("From", "<sip:u@e>;tag=t"),
                SipMessage.Header("To", "<sip:u@e>")
            )
        )
        transport.send(probe)

        // Server receives, then sends a 200 OK back
        val buf = ByteArray(8192)
        val inbox = DatagramPacket(buf, buf.size)
        mockServer.soTimeout = 5000
        mockServer.receive(inbox)
        val clientAddr = inbox.address
        val clientPort = inbox.port

        val resp = SipResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = listOf(
                SipMessage.Header("Via", "SIP/2.0/UDP 127.0.0.1:0"),
                SipMessage.Header("Call-ID", "probe"),
                SipMessage.Header("CSeq", "1 OPTIONS"),
                SipMessage.Header("From", "<sip:u@e>;tag=t"),
                SipMessage.Header("To", "<sip:u@e>;tag=server")
            )
        )
        val respBytes = resp.toBytes()
        mockServer.send(DatagramPacket(respBytes, respBytes.size, clientAddr, clientPort))

        // Transport should emit it on the incoming flow (SipEnvelope carries source)
        val envelope = withTimeout(5000) { transport.incoming.first() }
        val incoming = envelope.message
        assertTrue(incoming is SipResponse)
        assertEquals(200, incoming.statusCode)
        assertEquals("probe", incoming.callId())
        // Wave 7B P0-1:envelope 携带 datagram 来源 — host 可能是 "127.0.0.1" 也可能是 "localhost"(ktor 反向 DNS)
        assertTrue(
            envelope.sourceIp == "127.0.0.1" || envelope.sourceIp == "localhost",
            "sourceIp should be 127.0.0.1 or localhost, was ${envelope.sourceIp}"
        )
        assertEquals(mockServer.localPort, envelope.sourcePort)
        assertEquals(TransportType.UDP, envelope.transport)

        transport.close()
    }

    @Test fun closeReleasesSocket() = runBlocking {
        val transport = UdpSipTransport(
            remote = RemoteEndpoint("127.0.0.1", mockServer.localPort, TransportType.UDP),
            parentScope = scope
        )
        transport.connect()
        val firstPort = transport.localPort
        assertTrue(firstPort > 0)
        transport.close()
        // After close, can connect again on a (possibly different) port
        transport.connect()
        assertTrue(transport.localPort > 0)
        transport.close()
    }

    @Test fun connectIsIdempotent() = runBlocking {
        val transport = UdpSipTransport(
            remote = RemoteEndpoint("127.0.0.1", mockServer.localPort, TransportType.UDP),
            parentScope = scope
        )
        transport.connect()
        val port1 = transport.localPort
        transport.connect()  // second call should be no-op
        val port2 = transport.localPort
        assertEquals(port1, port2)
        transport.close()
    }

    // ----- T04 — NETWORK emit 验证 -----

    @Test fun connectSuccessEmitsNetworkInfo() = runBlocking {
        SystemLogger.resetForTest()
        SystemLogger.bindScope(scope)
        val transport = UdpSipTransport(
            remote = RemoteEndpoint("127.0.0.1", mockServer.localPort, TransportType.UDP),
            parentScope = scope
        )
        try {
            transport.connect()
            // 给 actor 一点时间 emit
            withTimeout(2000) {
                while (SystemLogger.snapshot.none {
                        it.tag == LogTag.Network && it.level == LogLevel.Info &&
                            it.message.contains("UDP socket bound")
                    }) {
                    kotlinx.coroutines.delay(20)
                }
            }
            val log = SystemLogger.snapshot.first {
                it.tag == LogTag.Network && it.level == LogLevel.Info
            }
            assertTrue("UDP socket bound" in log.message)
            assertTrue("127.0.0.1:${mockServer.localPort}" in log.message)
        } finally {
            transport.close()
            SystemLogger.resetForTest()
        }
    }

    @Test fun bindFailureEmitsNetworkError() = runBlocking {
        SystemLogger.resetForTest()
        SystemLogger.bindScope(scope)
        // 占用一个端口,再让另一个 transport 强行 bind 同口,触发失败
        val taken = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            val transport = UdpSipTransport(
                remote = RemoteEndpoint("127.0.0.1", mockServer.localPort, TransportType.UDP),
                localBindPort = taken.localPort,
                parentScope = scope
            )
            try {
                transport.connect()
            } catch (_: Throwable) {
                // 预期的 bind 失败
            }
            withTimeout(2000) {
                while (SystemLogger.snapshot.none {
                        it.tag == LogTag.Network && it.level == LogLevel.Error
                    }) {
                    kotlinx.coroutines.delay(20)
                }
            }
            val err = SystemLogger.snapshot.first {
                it.tag == LogTag.Network && it.level == LogLevel.Error
            }
            assertTrue("UDP bind 失败" in err.message, "actual: ${err.message}")
        } finally {
            taken.close()
            SystemLogger.resetForTest()
        }
    }
}

package com.uvp.sim.network

import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave 7B P0-1:[SipTransport.incoming] 类型从 `Flow<SipMessage>` 改 `Flow<SipEnvelope>` 后,
 * UDP transport 在 receive 时应填充 [SipEnvelope.sourceIp] / [SipEnvelope.sourcePort] /
 * [SipEnvelope.transport]。
 *
 * 这些是真 socket 集成测试(jvmTest 唯一可跑 Ktor UDP 的地方,iosTest 走 platform sockets)。
 *
 * 用 java.net DatagramSocket 模拟"上级 SIP 平台"主动发包给 transport 监听端口,
 * 断言上抛的 envelope 携带的 sourceIp/Port 就是 mock 平台的 host/port。
 */
class SipTransportEnvelopeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))

    @AfterTest fun tearDown() { server.close() }

    private fun buildOptionsResponse(callId: String): SipResponse = SipResponse(
        statusCode = 200,
        reasonPhrase = "OK",
        headers = listOf(
            SipMessage.Header("Via", "SIP/2.0/UDP 127.0.0.1:0"),
            SipMessage.Header("Call-ID", callId),
            SipMessage.Header("CSeq", "1 OPTIONS"),
            SipMessage.Header("From", "<sip:u@e>;tag=t"),
            SipMessage.Header("To", "<sip:u@e>;tag=s"),
        ),
    )

    private fun probeOptions(callId: String): SipRequest = SipRequest(
        method = SipMethod.OPTIONS,
        requestUri = "sip:test@example.com",
        headers = listOf(
            SipMessage.Header("Via", "SIP/2.0/UDP 127.0.0.1:0"),
            SipMessage.Header("Call-ID", callId),
            SipMessage.Header("CSeq", "1 OPTIONS"),
            SipMessage.Header("From", "<sip:u@e>;tag=t"),
            SipMessage.Header("To", "<sip:u@e>"),
        ),
    )

    /** P0-1:UDP envelope 必须携带 source IP/port = 实际 datagram 来源(不是 remote endpoint)。 */
    @Test fun udp_envelope_carries_real_datagram_source() = runBlocking {
        val transport = UdpSipTransport(
            remote = RemoteEndpoint("127.0.0.1", server.localPort, TransportType.UDP),
            parentScope = scope,
        )
        transport.connect()

        // 1) transport → server 一发,让 server 学到 client 端口
        transport.send(probeOptions("e1"))

        val buf = ByteArray(8192)
        val pkt = DatagramPacket(buf, buf.size)
        server.soTimeout = 5000
        server.receive(pkt)
        val clientAddr = pkt.address
        val clientPort = pkt.port

        // 2) server 主动回 200 OK,transport.incoming 应捕到 envelope
        val respBytes = buildOptionsResponse("e1").toBytes()
        server.send(DatagramPacket(respBytes, respBytes.size, clientAddr, clientPort))

        val env = withTimeout(5000) { transport.incoming.first() }
        assertTrue(env.message is SipResponse)
        assertTrue(
            env.sourceIp == "127.0.0.1" || env.sourceIp == "localhost",
            "P0-1: envelope sourceIp 应反映 mock 平台的 host (127.0.0.1 / localhost),实际=${env.sourceIp}"
        )
        assertEquals(server.localPort, env.sourcePort, "P0-1: envelope sourcePort 应为 mock 平台端口")
        assertEquals(TransportType.UDP, env.transport, "P0-1: envelope transport 应是 UDP")
        transport.close()
    }

    /** P0-1:多个 datagram 来源,每条都各自携带正确的 sourceIp/Port。 */
    @Test fun udp_envelope_distinguishes_multiple_sources() = runBlocking {
        // 第二个 mock server 模拟另一个平台
        val server2 = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            val transport = UdpSipTransport(
                remote = RemoteEndpoint("127.0.0.1", server.localPort, TransportType.UDP),
                parentScope = scope,
            )
            transport.connect()

            // probe 让两个 server 都知道 client 端口
            transport.send(probeOptions("e2-a"))

            val buf1 = ByteArray(8192)
            val pkt1 = DatagramPacket(buf1, buf1.size)
            server.soTimeout = 5000
            server.receive(pkt1)
            val clientAddr = pkt1.address
            val clientPort = pkt1.port

            // server1 回 200
            val resp1 = buildOptionsResponse("e2-a").toBytes()
            server.send(DatagramPacket(resp1, resp1.size, clientAddr, clientPort))

            // server2 主动也回 200(模拟来自非配置 endpoint 的攻击源)
            val resp2 = buildOptionsResponse("e2-b").toBytes()
            server2.send(DatagramPacket(resp2, resp2.size, clientAddr, clientPort))

            // 收两条 envelope,各自的 sourcePort 应该对得上
            val collected = withTimeout(5000) {
                transport.incoming.take(2).toList()
            }
            // 两条 envelope,sourcePort 应为 server.localPort 和 server2.localPort(顺序不保证)
            val ports = collected.map { it.sourcePort }.toSet()
            assertTrue(server.localPort in ports, "应捕到 server1 的 sourcePort")
            assertTrue(server2.localPort in ports, "应捕到 server2 的 sourcePort")
            transport.close()
        } finally {
            server2.close()
        }
    }

    /** P0-1:envelope.message 仍是原解析出的 SipMessage,sourceIp 是新增字段,不影响业务解析。 */
    @Test fun udp_envelope_preserves_parsed_message_semantics() = runBlocking {
        val transport = UdpSipTransport(
            remote = RemoteEndpoint("127.0.0.1", server.localPort, TransportType.UDP),
            parentScope = scope,
        )
        transport.connect()

        transport.send(probeOptions("e3"))
        val buf = ByteArray(8192)
        val pkt = DatagramPacket(buf, buf.size)
        server.soTimeout = 5000
        server.receive(pkt)
        val respBytes = buildOptionsResponse("e3").toBytes()
        server.send(DatagramPacket(respBytes, respBytes.size, pkt.address, pkt.port))

        val env = withTimeout(5000) { transport.incoming.first() }
        val msg = env.message
        assertTrue(msg is SipResponse)
        assertEquals(200, msg.statusCode)
        assertEquals("e3", msg.callId())
        transport.close()
    }
}

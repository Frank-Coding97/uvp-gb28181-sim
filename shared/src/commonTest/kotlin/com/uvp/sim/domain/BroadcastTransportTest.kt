package com.uvp.sim.domain

import com.uvp.sim.config.AudioTransportType
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/** 对讲媒体传输模式(UDP / TCP 主动 / TCP 被动)的 engine 接线。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastTransportTest {

    private val deviceId = "34020000001310000001"
    private val channelId = "34020000001320000001"
    private val platformId = "34020000002000000001"
    private val domain = "3402000000"

    private fun cfg(transport: AudioTransportType) = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(ip = "192.168.10.222", port = 5060, serverId = platformId, domain = domain),
        device = DeviceConfig(
            deviceId = deviceId, videoChannelId = channelId,
            alarmChannelId = "34020000001340000001", username = deviceId, password = "p"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        audioTransport = transport
    )

    private fun broadcastMessage(): SipRequest {
        val body = """<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Broadcast</CmdType>
<SN>1</SN>
<SourceID>$platformId</SourceID>
<TargetID>$channelId</TargetID>
</Notify>""".replace("\n", "\r\n")
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:$deviceId@$domain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:5060;branch=z9hG4bK-bc"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformId@$domain>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:$deviceId@$domain>"),
                SipMessage.Header(SipHeader.CALL_ID, "bc@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "application/MANSCDP+xml")
            ),
            body = body.encodeToByteArray()
        )
    }

    /** 平台 answer:TCP 被动监听 10.0.0.5:30100,设备主动连。 */
    private fun ok200Tcp(invite: SipRequest): SipResponse = SipResponse(
        statusCode = 200, reasonPhrase = "OK",
        headers = invite.headers.filter {
            val c = SipHeader.canonicalize(it.name)
            c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.CALL_ID || c == SipHeader.CSEQ
        } + SipMessage.Header(SipHeader.TO, (invite.toHeader() ?: "<sip:p@d>") + ";tag=platbc") +
            SipMessage.Header(SipHeader.CONTACT, "<sip:$platformId@10.0.0.5:5060>"),
        body = """v=0
c=IN IP4 10.0.0.5
m=audio 30100 TCP/RTP/AVP 8
a=rtpmap:8 PCMA/8000
a=sendonly
a=setup:passive
""".trimIndent().replace("\n", "\r\n").encodeToByteArray()
    )

    private suspend fun bootRegistered(transport: MockSipTransport, engine: SimulatorEngine) {
        transport.connect()
        engine.register()
        val regReq = transport.sent.filterIsInstance<SipRequest>().first { it.method == SipMethod.REGISTER }
        val ok = SipResponse(
            statusCode = 200, reasonPhrase = "OK",
            headers = regReq.headers.filter {
                val c = SipHeader.canonicalize(it.name)
                c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.CALL_ID || c == SipHeader.CSEQ
            } + SipMessage.Header(SipHeader.TO, (regReq.toHeader() ?: "<sip:u@e>") + ";tag=server")
        )
        transport.deliver(ok)
    }

    private fun lastInviteBody(transport: MockSipTransport): String =
        transport.sent.filterIsInstance<SipRequest>().last { it.method == SipMethod.INVITE }.body.decodeToString()

    private fun lastInvite(transport: MockSipTransport): SipRequest =
        transport.sent.filterIsInstance<SipRequest>().last { it.method == SipMethod.INVITE }

    /**
     * baseline red · task 12:iOS 真实 AudioSink (AVAudioEngine) 在 simulator 启动失败,
     * 走 R1 #7 "sink.start fail → BYE + teardown" 路径,把 _current 清 null。
     */
    private val fakeSink: (Int, Int) -> com.uvp.sim.media.AudioSink = { _, _ -> FakeAudioSink() }

    @Test
    fun tcpActiveOffersTcpAndConnectsToPlatform() = runTest {
        val transport = MockSipTransport()
        val fakeRx = FakeBroadcastRxSource()
        val engine = TestEngine.create(
            cfg(AudioTransportType.TCP_ACTIVE), transport, this, localIpProvider = { "192.168.10.112" },
            rtpReceiverFactory = { fakeRx },
            audioSinkFactory = fakeSink,
        )
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()

        // offer 应是 TCP/RTP/AVP + a=setup:active
        val offer = lastInviteBody(transport)
        assertTrue(offer.contains("TCP/RTP/AVP"), "TCP 主动 offer 应为 TCP/RTP/AVP")
        assertTrue(offer.contains("a=setup:active"), "TCP 主动 offer 应 a=setup:active")
        assertEquals(RtpMode.TCP_ACTIVE, fakeRx.boundMode)

        // 200 OK(平台给 IP:端口)→ 设备主动 connect
        transport.deliver(ok200Tcp(lastInvite(transport)))
        runCurrent()
        assertEquals(1, fakeRx.connectCount, "TCP 主动应 connect 平台一次")
        assertEquals("10.0.0.5", fakeRx.connectedHost)
        assertEquals(30100, fakeRx.connectedPort)
        assertEquals(BroadcastDialogState.Talking, engine.currentBroadcast.value?.state)
        engine.stopBroadcast(BroadcastEndReason.Local)
        runCurrent()
        engine.shutdown()
    }

    @Test
    fun udpOffersUdpAndDoesNotConnect() = runTest {
        val transport = MockSipTransport()
        val fakeRx = FakeBroadcastRxSource()
        val engine = TestEngine.create(
            cfg(AudioTransportType.UDP), transport, this, localIpProvider = { "192.168.10.112" },
            rtpReceiverFactory = { fakeRx },
            audioSinkFactory = fakeSink,
        )
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()

        val offer = lastInviteBody(transport)
        assertTrue(offer.contains("RTP/AVP") && !offer.contains("TCP/RTP/AVP"), "UDP offer 应为 RTP/AVP")
        assertTrue(!offer.contains("a=setup:"), "UDP offer 不应有 a=setup")
        assertEquals(RtpMode.UDP, fakeRx.boundMode)

        transport.deliver(ok200Tcp(lastInvite(transport)))
        runCurrent()
        assertEquals(0, fakeRx.connectCount, "UDP 不应 connect")
        engine.stopBroadcast(BroadcastEndReason.Local)
        runCurrent()
        engine.shutdown()
    }

    @Test
    fun tcpPassiveOffersPassiveAndDoesNotConnect() = runTest {
        val transport = MockSipTransport()
        val fakeRx = FakeBroadcastRxSource()
        val engine = TestEngine.create(
            cfg(AudioTransportType.TCP_PASSIVE), transport, this, localIpProvider = { "192.168.10.112" },
            rtpReceiverFactory = { fakeRx },
            audioSinkFactory = fakeSink,
        )
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()

        val offer = lastInviteBody(transport)
        assertTrue(offer.contains("TCP/RTP/AVP"), "TCP 被动 offer 应为 TCP/RTP/AVP")
        assertTrue(offer.contains("a=setup:passive"), "TCP 被动 offer 应 a=setup:passive")
        assertEquals(RtpMode.TCP_PASSIVE, fakeRx.boundMode)

        transport.deliver(ok200Tcp(lastInvite(transport)))
        runCurrent()
        assertEquals(0, fakeRx.connectCount, "TCP 被动不主动 connect(等平台连入)")
        engine.stopBroadcast(BroadcastEndReason.Local)
        runCurrent()
        engine.shutdown()
    }
}

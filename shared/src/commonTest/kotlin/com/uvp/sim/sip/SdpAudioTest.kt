package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** T2 — SDP audio offer/answer(语音广播反向流)。 */
class SdpAudioTest {

    @Test
    fun buildBroadcastOfferEmitsDualPayloadTypes() {
        val sdp = SdpAnswer.buildBroadcastOffer(
            deviceId = "34020000001320000001",
            localIp = "192.168.1.10",
            localAudioPort = 30002,
            deviceSsrc = "0123456789"
        )
        assertContains(sdp, "v=0\r\n")
        assertContains(sdp, "s=Broadcast\r\n")
        assertContains(sdp, "c=IN IP4 192.168.1.10\r\n")
        assertContains(sdp, "m=audio 30002 RTP/AVP 8 0\r\n")
        assertContains(sdp, "a=rtpmap:8 PCMA/8000\r\n")
        assertContains(sdp, "a=rtpmap:0 PCMU/8000\r\n")
        assertContains(sdp, "a=recvonly\r\n")
        assertContains(sdp, "y=0123456789\r\n")
    }

    @Test
    fun parseAnswerExtractsAudioEndpointAndCodec() {
        val ans = """v=0
o=server 0 0 IN IP4 10.0.0.5
s=Broadcast
c=IN IP4 10.0.0.5
t=0 0
m=audio 30100 RTP/AVP 8
a=rtpmap:8 PCMA/8000
a=sendonly
""".trimIndent()
        val parsed = SdpParser.parseAnswer(ans)
        assertEquals("10.0.0.5", parsed.remoteIp)
        assertEquals(30100, parsed.remotePort)
        assertEquals(SdpMediaType.AUDIO, parsed.mediaType)
        assertEquals(listOf(8), parsed.payloadTypes)
        assertEquals(SdpDirection.SENDONLY, parsed.direction)
    }

    @Test
    fun parseAnswerKeepsNonG711PayloadType() {
        val ans = """v=0
c=IN IP4 10.0.0.5
m=audio 30100 RTP/AVP 96
a=rtpmap:96 opus/48000
a=sendonly
""".trimIndent()
        val parsed = SdpParser.parseAnswer(ans)
        assertEquals(listOf(96), parsed.payloadTypes)
        // 状态机(T3)负责检测 payloadTypes ∉ {0, 8} 后发 BYE,这里只保 SDP 解析
    }

    @Test
    fun parseOfferStillHandlesVideo() {
        val offer = """v=0
c=IN IP4 1.2.3.4
m=video 9000 RTP/AVP 96
a=rtpmap:96 PS/90000
a=sendrecv
y=0100000001
""".trimIndent()
        val parsed = SdpParser.parseOffer(offer)
        assertEquals(SdpMediaType.VIDEO, parsed.mediaType)
        assertEquals(9000, parsed.remotePort)
        assertEquals(listOf(96), parsed.payloadTypes)
    }

    @Test
    fun buildBroadcastOfferTcpActive() {
        val sdp = SdpAnswer.buildBroadcastOffer(
            deviceId = "34020000001320000001",
            localIp = "192.168.1.10",
            localAudioPort = 0,                  // TCP 主动:不监听,端口占位 0
            deviceSsrc = "0123456789",
            transport = SdpTransport.TCP,
            tcpSetup = SdpTcpSetup.ACTIVE
        )
        assertContains(sdp, "m=audio 0 TCP/RTP/AVP 8 0\r\n")
        assertContains(sdp, "a=rtpmap:8 PCMA/8000\r\n")
        assertContains(sdp, "a=recvonly\r\n")
        assertContains(sdp, "a=setup:active\r\n")
        assertContains(sdp, "a=connection:new\r\n")
        assertContains(sdp, "y=0123456789\r\n")
    }

    @Test
    fun buildBroadcastOfferTcpPassive() {
        val sdp = SdpAnswer.buildBroadcastOffer(
            deviceId = "34020000001320000001",
            localIp = "192.168.1.10",
            localAudioPort = 40000,              // TCP 被动:监听端口
            deviceSsrc = "0123456789",
            transport = SdpTransport.TCP,
            tcpSetup = SdpTcpSetup.PASSIVE
        )
        assertContains(sdp, "m=audio 40000 TCP/RTP/AVP 8 0\r\n")
        assertContains(sdp, "a=setup:passive\r\n")
        assertContains(sdp, "a=connection:new\r\n")
    }

    @Test
    fun parseAnswerTcpPassivePlatform() {
        // 平台 answer:TCP 被动(监听),设备主动连
        val ans = """v=0
c=IN IP4 10.0.0.5
m=audio 30100 TCP/RTP/AVP 8
a=rtpmap:8 PCMA/8000
a=sendonly
a=setup:passive
""".trimIndent()
        val parsed = SdpParser.parseAnswer(ans)
        assertEquals(SdpTransport.TCP, parsed.transport)
        assertEquals("10.0.0.5", parsed.remoteIp)
        assertEquals(30100, parsed.remotePort)
        assertEquals(SdpTcpSetup.PASSIVE, parsed.tcpSetup)
    }
}

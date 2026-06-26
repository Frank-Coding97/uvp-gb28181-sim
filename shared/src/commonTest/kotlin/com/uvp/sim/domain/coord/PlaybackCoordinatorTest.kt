package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * PR5 T5.2 GREEN:[PlaybackCoordinatorImpl] 直接路径覆盖。正向断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.10.222", port = 8160,
            serverId = "35020000002000000001", domain = "3502000000",
        ),
        device = DeviceConfig(
            deviceId = "35020000001310000001",
            videoChannelId = "35020000001320000001",
            alarmChannelId = "35020000001340000001",
            username = "35020000001310000001",
            password = "p",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
    )

    private fun newPb(scope: CoroutineScope, transport: MockSipTransport): PlaybackCoordinatorImpl =
        PlaybackCoordinatorImpl(
            config = config(),
            transport = transport,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            scope = scope,
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
        )

    private fun playbackInvite(callId: String, channelId: String = "35020000001320000001"): SipRequest {
        val sdp = """
            v=0
            o=server 0 0 IN IP4 192.168.10.222
            s=Playback
            u=$channelId:0
            c=IN IP4 192.168.10.222
            t=1700000000 1700001000
            m=video 30000 RTP/AVP 96
            a=recvonly
            a=rtpmap:96 PS/90000
            y=0100000001
        """.trimIndent().replace("\n", "\r\n")
        return SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:$channelId@3502000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bK-pb"),
                SipMessage.Header(SipHeader.FROM, "<sip:35020000002000000001@3502000000>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:$channelId@3502000000>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
                SipMessage.Header(SipHeader.CONTACT, "<sip:35020000002000000001@192.168.10.222:8160>"),
                SipMessage.Header("Content-Type", "application/sdp"),
            ),
            body = sdp.encodeToByteArray(),
        )
    }

    @Test
    fun t5_2_pb_a_handlePlaybackInvite_no_builder_returns_487() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val pb = newPb(this, transport)
        val result = pb.onIncoming(playbackInvite("pb-1@plat"))
        runCurrent()
        assertEquals(RoutingResult.Handled, result, "Playback INVITE 应被 Coord 吃下")
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp, "应有响应")
        assertEquals(487, resp.statusCode, "无 playbackBuilder 应 487")
    }

    @Test
    fun t5_2_pb_b_play_INVITE_returns_Skip() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val pb = newPb(this, transport)
        // s=Play 的 INVITE,Playback 必须 Skip 让 Invite 接
        val sdp = """
            v=0
            o=server 0 0 IN IP4 192.168.10.222
            s=Play
            c=IN IP4 192.168.10.222
            t=0 0
            m=video 30000 RTP/AVP 96
            a=recvonly
            a=rtpmap:96 PS/90000
            y=0100000001
        """.trimIndent().replace("\n", "\r\n")
        val req = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:35020000001320000001@3502000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bK-play"),
                SipMessage.Header(SipHeader.FROM, "<sip:35020000002000000001@3502000000>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:35020000001320000001@3502000000>"),
                SipMessage.Header(SipHeader.CALL_ID, "play-1@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
                SipMessage.Header("Content-Type", "application/sdp"),
            ),
            body = sdp.encodeToByteArray(),
        )
        val result = pb.onIncoming(req)
        runCurrent()
        assertEquals(RoutingResult.Skip, result, "Play INVITE 必须 Skip 给 Invite")
    }

    @Test
    fun t5_2_pb_c_handleInfo_no_active_returns_Skip() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val pb = newPb(this, transport)
        val info = SipRequest(
            method = SipMethod.INFO,
            requestUri = "sip:35020000001310000001@3502000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bK-info"),
                SipMessage.Header(SipHeader.FROM, "<sip:35020000002000000001@3502000000>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:35020000001320000001@3502000000>;tag=local"),
                SipMessage.Header(SipHeader.CALL_ID, "no-such-pb@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 INFO"),
            ),
            body = ByteArray(0),
        )
        val result = pb.onIncoming(info)
        runCurrent()
        assertEquals(RoutingResult.Skip, result, "无 activePlayback 必须 Skip 让 Mans 接")
    }

    @Test
    fun t5_2_pb_d_stop_with_no_active_is_noop() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val pb = newPb(this, transport)
        pb.stop("user stop")
        runCurrent()
        // 无活跃 → no-op,不发任何消息,状态保持 Idle
        assertEquals(0, transport.sent.size, "无活跃回放时 stop 不应发消息")
        assertEquals(PlaybackState.Idle, pb.state.value)
    }
}

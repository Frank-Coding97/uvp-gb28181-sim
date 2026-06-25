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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * PR5 T5.1 RED:[PlaybackCoordinatorImpl] 直接路径覆盖。
 *
 * GREEN 后(T5.2)改正向断言。
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
            scope = scope,
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
        )

    private fun playbackInvite(callId: String, channelId: String = "35020000001320000001"): SipRequest {
        // s=Playback + t=<startEpochSec> <endEpochSec>
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
    fun t5_1_pb_a_handlePlaybackInvite_no_segments_returns_487() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val pb = newPb(this, transport)
        // RED:onIncoming 抛错 → assertFails 通过
        // GREEN:transport.sent 含 487 响应(playbackBuilder=null 走 487 路径)
        assertFails("RED: handlePlaybackInvite stub 应抛错") {
            pb.onIncoming(playbackInvite("pb-1@plat"))
        }
    }

    @Test
    fun t5_1_pb_b_handleInfo_no_active_returns_Skip() = runTest {
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
        // RED:抛错;GREEN:无 activePlayback 必须 Skip 让 Mans 接
        assertFails("RED: onIncoming stub 应抛错") { pb.onIncoming(info) }
    }

    @Test
    fun t5_1_pb_c_stop_with_no_active_is_noop() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val pb = newPb(this, transport)
        // RED:抛错;GREEN:stop 无活跃回放是 no-op
        assertFails("RED: stop stub 应抛错") { pb.stop("user stop") }
    }

    @Test
    fun t5_1_pb_d_initial_state_is_Idle() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val pb = newPb(this, transport)
        // 初始状态固定 Idle,不抛错
        assertEquals(PlaybackState.Idle, pb.state.value, "初始状态必须 Idle")
        runCurrent()
    }
}

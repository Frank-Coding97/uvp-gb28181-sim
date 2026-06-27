package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.testing.asEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * PR5 T5.4:[InviteCoordinatorImpl] 直接路径覆盖,Invite 退出广播 / 回放域。
 *
 * 直播 INVITE / 488 reject / Skip(Playback)/ ACK / stopStream 全部留本类;
 * fireBroadcastInvite 删除(归 BroadcastCoordinator),用 BroadcastCoordinatorTest 验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteCoordinatorTest {

    private fun config(catalog: List<CatalogNode> = emptyList()) = SimConfig(
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
        catalogTree = catalog,
    )

    private fun fullTree() = listOf(
        CatalogNode("35020000001310000001", CatalogNodeType.Device, "Dev", "35020000001310000001"),
        CatalogNode("35020000001320000001", CatalogNodeType.VideoChannel, "Cam", "35020000001310000001"),
        CatalogNode("35020000001340000001", CatalogNodeType.AlarmChannel, "Alm", "35020000001310000001"),
    )

    private fun newInvite(
        scope: CoroutineScope,
        transport: MockSipTransport,
        catalog: List<CatalogNode> = fullTree(),
        cameraCapture: com.uvp.sim.camera.CameraCapture? = null,
        rtpSenderFactory: ((String, Int, RtpMode, String?) -> RtpSender)? = null,
    ): InviteCoordinatorImpl {
        val cfg = config(catalog)
        val tree = MutableStateFlow(catalog)
        val sipState = MutableStateFlow(com.uvp.sim.sip.SipState.Registered)
        return InviteCoordinatorImpl(
            config = cfg,
            transport = transport,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            scope = scope,
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
            cameraCapture = cameraCapture,
            audioCapture = null,
            rtpSenderFactory = rtpSenderFactory,
            catalogTree = tree,
            mutableSipState = sipState,
            simEventEmit = {},
        )
    }

    private fun inviteFor(channelId: String, callId: String = "inv-$channelId@plat"): SipRequest {
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
        return SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:$channelId@3502000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bK-inv"),
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
    fun handleInvite_video_channel_handled() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)
        val result = invite.onIncoming(inviteFor("35020000001320000001").asEnvelope())
        runCurrent()
        assertEquals(RoutingResult.Handled, result, "Play INVITE 应被 Coord 吃下")
        val rejections = transport.sent.filterIsInstance<SipResponse>().filter { it.statusCode == 488 }
        assertEquals(0, rejections.size, "视频通道不应被 488 拒绝")
    }

    @Test
    fun handleInvite_alarm_channel_returns_488() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)
        invite.onIncoming(inviteFor("35020000001340000001").asEnvelope())
        runCurrent()
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp)
        assertEquals(488, resp.statusCode, "报警通道应返回 488")
    }

    @Test
    fun handleInvite_alarm_two_times_both_488() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)
        invite.onIncoming(inviteFor("35020000001340000001", callId = "first@plat").asEnvelope())
        invite.onIncoming(inviteFor("35020000001340000001", callId = "second@plat").asEnvelope())
        runCurrent()
        val rejects = transport.sent.filterIsInstance<SipResponse>().filter { it.statusCode == 488 }
        assertEquals(2, rejects.size, "两次 alarm INVITE 都应 488")
    }

    @Test
    fun handlePlaybackInvite_returns_Skip() = runTest {
        // PR5 T5.4:Invite 收到 SDP s=Playback 必须 Skip 让 Engine 路由给 PlaybackCoordinator
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)
        val sdp = """
            v=0
            o=server 0 0 IN IP4 192.168.10.222
            s=Playback
            u=35020000001320000001:0
            c=IN IP4 192.168.10.222
            t=1700000000 1700001000
            m=video 30000 RTP/AVP 96
            a=recvonly
            a=rtpmap:96 PS/90000
            y=0100000001
        """.trimIndent().replace("\n", "\r\n")
        val req = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:35020000001320000001@3502000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bK-pb"),
                SipMessage.Header(SipHeader.FROM, "<sip:35020000002000000001@3502000000>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:35020000001320000001@3502000000>"),
                SipMessage.Header(SipHeader.CALL_ID, "pb-1@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
                SipMessage.Header("Content-Type", "application/sdp"),
            ),
            body = sdp.encodeToByteArray(),
        )
        val result = invite.onIncoming(req.asEnvelope())
        runCurrent()
        assertEquals(RoutingResult.Skip, result, "Playback INVITE 必须 Skip 让 Engine 路由 PlaybackCoordinator")
    }

    @Test
    fun handleAck_no_active_returns_Handled() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)
        val ack = SipRequest(
            method = SipMethod.ACK,
            requestUri = "sip:35020000001310000001@3502000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bK-ack"),
                SipMessage.Header(SipHeader.FROM, "<sip:35020000002000000001@3502000000>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:35020000001320000001@3502000000>;tag=local"),
                SipMessage.Header(SipHeader.CALL_ID, "no-such-call@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 ACK"),
            ),
            body = ByteArray(0),
        )
        val result = invite.onIncoming(ack.asEnvelope())
        runCurrent()
        assertEquals(RoutingResult.Handled, result, "ACK 必须归 Invite 域(Handled)")
    }

    @Test
    fun stopStream_with_no_active_is_noop() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)
        invite.stopStream("user stop")
        runCurrent()
        val byes = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.BYE }
        assertEquals(0, byes.size, "无活跃流时 stopStream 不应发 BYE")
        assertEquals(InviteState.Idle, invite.state.value)
        assertNull(invite.activeStreamSnapshot.value)
    }

    // ---------- H-1 (PR-SEC-1):From 头身份校验 ----------

    private fun inviteWithFrom(
        channelId: String,
        fromHeader: String,
        callId: String = "spoof-$channelId@bad",
    ): SipRequest {
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
        return SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:$channelId@3502000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 10.1.2.3:6000;branch=z9hG4bK-spoof"),
                SipMessage.Header(SipHeader.FROM, fromHeader),
                SipMessage.Header(SipHeader.TO, "<sip:$channelId@3502000000>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
                SipMessage.Header(SipHeader.CONTACT, "<sip:attacker@10.1.2.3:6000>"),
                SipMessage.Header("Content-Type", "application/sdp"),
            ),
            body = sdp.encodeToByteArray(),
        )
    }

    @Test
    fun handleInvite_spoofed_from_host_rejected_403() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)
        // 攻击者用对的 user 但错的 host(伪造非授权域)
        val req = inviteWithFrom(
            channelId = "35020000001320000001",
            fromHeader = "<sip:35020000002000000001@attacker.bad>;tag=evil",
        )
        val result = invite.onIncoming(req.asEnvelope())
        runCurrent()
        assertEquals(RoutingResult.Handled, result, "伪造 INVITE 应被 Coord 处理(返 403)")
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp, "应返回响应")
        assertEquals(403, resp.statusCode, "伪造 From host 必须 403 Forbidden")
    }

    @Test
    fun handleInvite_spoofed_from_user_rejected_403() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)
        // 攻击者用对的 host 但伪造的 user + 攻击 IP(GB28181 实践里平台经常用别名,
        // 所以单纯换 user 不一定能命中校验 — 但攻击者从外网域 + IP 伪造仍会被 host 校验拦)
        val req = inviteWithFrom(
            channelId = "35020000001320000001",
            fromHeader = "<sip:99999999991111111111@attacker.bad>;tag=evil",
        )
        val result = invite.onIncoming(req.asEnvelope())
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp)
        assertEquals(403, resp.statusCode, "伪造 From host 必须 403 Forbidden")
    }

    @Test
    fun handleInvite_authorized_from_proceeds() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)
        // 正常配置好的平台 From:user=serverId,host=domain → 走正常流程,不应 403
        val req = inviteWithFrom(
            channelId = "35020000001320000001",
            fromHeader = "<sip:35020000002000000001@3502000000>;tag=plat",
        )
        invite.onIncoming(req.asEnvelope())
        runCurrent()
        val rejections = transport.sent.filterIsInstance<SipResponse>().filter { it.statusCode == 403 }
        assertEquals(0, rejections.size, "授权平台的 INVITE 不应被 403 拦截")
    }
}

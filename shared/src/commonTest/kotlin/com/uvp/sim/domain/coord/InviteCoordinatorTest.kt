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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PR4 T4.1 RED:[InviteCoordinatorImpl] 直接路径覆盖。
 *
 * 这里只测 Coord 独有的契约(handleInvite Play / classifyInviteTarget reject /
 * busy 拒绝 / fireBroadcastInvite 发反向 INVITE / handleAck 取消 watchdog /
 * stopStream 发 BYE)。详细媒体管线 / RTP 字节流 / dialog state 走 Engine 既有 contract test。
 *
 * **当前所有用例都对空 stub 跑,期望 NotImplementedError**(T4.1 RED 验证测试用例本身有效)。
 * T4.2 GREEN 时把 `assertFails {...}` 改成正向断言。
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
        rtpSenderFactory: ((String, Int, RtpMode) -> RtpSender)? = null,
        broadcastListener: BroadcastDialogHandshakeListener = NoopBroadcastDialogHandshakeListener,
        cseqProvider: (() -> Int)? = null,
        cseqIncrementer: (() -> Int)? = null,
    ): InviteCoordinatorImpl {
        val cfg = config(catalog)
        val tree = MutableStateFlow(catalog)
        val sipState = MutableStateFlow(com.uvp.sim.sip.SipState.Registered)
        return InviteCoordinatorImpl(
            config = cfg,
            transport = transport,
            scope = scope,
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
            cameraCapture = cameraCapture,
            audioCapture = null,
            rtpSenderFactory = rtpSenderFactory,
            playbackBuilder = null,
            catalogTree = tree,
            broadcastHandshakeListener = broadcastListener,
            mutableSipState = sipState,
            simEventEmit = {},
            cseqProvider = cseqProvider,
            cseqIncrementer = cseqIncrementer,
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

    private fun ackFor(callId: String): SipRequest = SipRequest(
        method = SipMethod.ACK,
        requestUri = "sip:35020000001310000001@3502000000",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bK-ack"),
            SipMessage.Header(SipHeader.FROM, "<sip:35020000002000000001@3502000000>;tag=plat"),
            SipMessage.Header(SipHeader.TO, "<sip:35020000001320000001@3502000000>;tag=local"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 ACK"),
        ),
        body = ByteArray(0),
    )

    // ----------------------------------------------------------------
    // T4.1 RED 用例(全部跑空 stub,期望 NotImplementedError 或行为不达成)
    // T4.2 GREEN 时改成正向断言
    // ----------------------------------------------------------------

    @Test
    fun t4_1_a_handleInvite_play_sends_200_with_sdp_answer() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)

        // RED:onIncoming 是空 stub,会抛 NotImplementedError → assertFails 通过
        // GREEN:断言 transport.sent 含 200 OK + SDP body
        assertFails("RED: onIncoming 空 stub 应抛错") {
            invite.onIncoming(inviteFor("35020000001320000001"))
        }
    }

    @Test
    fun t4_1_b_handleInvite_alarm_channel_returns_488() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)

        // RED:onIncoming 抛错 → 测试通过
        // GREEN:断言 transport.sent 含 488 响应
        assertFails("RED: 488 路径未实现") {
            invite.onIncoming(inviteFor("35020000001340000001"))
        }
    }

    @Test
    fun t4_1_c_handleInvite_busy_returns_486_when_activeStream_present() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)

        // RED:onIncoming 第一次就抛错(还谈不上 busy)→ 测试通过
        // GREEN:第一次 INVITE 走 200 OK,第二次 INVITE 同 deviceId 应回 486 Busy Here
        assertFails("RED: busy 拒绝路径未实现") {
            invite.onIncoming(inviteFor("35020000001320000001", callId = "first@plat"))
            // 即使第一次成功(GREEN),第二次也必须 fail-stop;RED 期望第一次就抛
            invite.onIncoming(inviteFor("35020000001320000001", callId = "second@plat"))
        }
    }

    @Test
    fun t4_1_d_fireBroadcastInvite_sends_outbound_INVITE_with_sdp_offer() = runTest {
        val transport = MockSipTransport()
        transport.connect()

        var listenerInviting = 0
        val listener = object : BroadcastDialogHandshakeListener {
            override suspend fun onInviting(
                callId: String, fromTag: String, cseq: Int, sourceId: String, targetId: String,
                platformUri: String, localAudioPort: Int, deviceSsrc: String, mode: RtpMode,
            ) { listenerInviting++ }
            override suspend fun onTalking(
                callId: String, remoteTag: String, remoteHost: String, remotePort: Int,
                codec: com.uvp.sim.domain.AudioRxCodec,
            ) {}
            override suspend fun onFailed(callId: String, reason: BroadcastEndReasonHint) {}
        }
        val invite = newInvite(this, transport, broadcastListener = listener)

        // RED:fireBroadcastInvite 抛错 → assertFails 通过
        // GREEN:transport.sent 含 outbound INVITE + listener.onInviting 被调一次
        assertFails("RED: fireBroadcastInvite 空 stub 应抛错") {
            invite.fireBroadcastInvite(
                sourceId = "35020000002000000001",
                platformUri = "sip:35020000002000000001@3502000000",
                targetId = "35020000001310000001",
            )
        }
        // GREEN 时此处应:
        //   assertEquals(1, listenerInviting, "listener.onInviting 必须被调一次")
        //   assertTrue(transport.sent.filterIsInstance<SipRequest>()
        //                .any { it.method == SipMethod.INVITE })
    }

    @Test
    fun t4_1_e_handleAck_cancels_ack_watchdog() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)

        // RED:onIncoming 抛错 → assertFails 通过
        // GREEN:先 onIncoming(INVITE) → activeStream 起 + ackTimeoutJob 起;
        //        再 onIncoming(ACK) → ackTimeoutJob 取消;过 ACK_TIMEOUT_MS 后无 InviteAckTimeout event
        assertFails("RED: ACK watchdog 未实现") {
            invite.onIncoming(ackFor("inv-cam@plat"))
        }
    }

    @Test
    fun t4_1_f_stopStream_sends_BYE_and_emits_Stopped() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val invite = newInvite(this, transport)

        // RED:stopStream 抛错 → assertFails 通过
        // GREEN:先建 activeStream(via onIncoming INVITE)→ stopStream → 发 BYE + emit Stopped
        assertFails("RED: stopStream 空 stub 应抛错") {
            invite.stopStream("user stop")
        }
    }
}

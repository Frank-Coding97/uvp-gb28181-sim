package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipHeaderHelpers
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
import kotlin.test.assertTrue

/**
 * Wave 7B P1-3:[InviteCoordinatorImpl] 直播 mid-dialog 请求 dialog identity 校验。
 *
 * 跟 [PlaybackDialogIdentityTest] 同款契约,只是作用于直播链路(s=Play)的 ACK / CANCEL / BYE:
 *  - 建立 INVITE 200 → activeStream 记录 callId / localTag / remoteTag / remoteSourceIp
 *  - mid-dialog ACK / CANCEL / BYE 进来:
 *     · callId + remoteTag + sourceIp 全对 → 处理
 *     · 任一不匹配 → CANCEL/BYE 481 Call/Transaction Does Not Exist + Warning,ACK 丢弃
 *
 * 攻击场景覆盖:
 *  - LAN 抓包拿到 Call-ID,攻击者发 BYE 强制结束直播会话(remoteTag 错)
 *  - 攻击者从非授权 IP 发 CANCEL(sourceIp 错)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteDialogIdentityTest {

    private val platformIp = "192.168.10.222"
    private val platformDomain = "3502000000"
    private val platformServerId = "35020000002000000001"
    private val channelId = "35020000001320000001"

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = platformIp, port = 8160,
            serverId = platformServerId, domain = platformDomain,
        ),
        device = DeviceConfig(
            deviceId = "35020000001310000001",
            videoChannelId = channelId,
            alarmChannelId = "35020000001340000001",
            username = "35020000001310000001",
            password = "p",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
    )

    private fun newInvite(
        scope: CoroutineScope,
        transport: MockSipTransport,
    ): InviteCoordinatorImpl {
        val cfg = config()
        val tree = MutableStateFlow(listOf(
            com.uvp.sim.config.CatalogNode(
                id = channelId,
                type = com.uvp.sim.config.CatalogNodeType.VideoChannel,
                name = "test-channel",
                parentId = "35020000001000000001",
            )
        ))
        val sipState = MutableStateFlow(com.uvp.sim.sip.SipState.Registered)
        val capture = com.uvp.sim.camera.CameraCapture(com.uvp.sim.camera.CaptureConfig())
        return InviteCoordinatorImpl(
            config = cfg,
            transport = transport,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            scope = scope,
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
            cameraCapture = capture,
            audioCapture = null,
            rtpSenderFactory = { host, port, mode, expectedClientHost ->
                com.uvp.sim.network.RtpSender(host, port, scope, mode, expectedClientHost)
            },
            catalogTree = tree,
            mutableSipState = sipState,
            simEventEmit = {},
            // baseline red · task 12:iOS simulator 上 CameraCapture.start() 抛异常会
            // 触发 onMediaFailure 清 activeStream,把 dialog identity 断言撕烂;本套测试
            // 只关心 SIP 层校验,LAZY 媒体 job 不启动即可。
            autoStartMediaJobs = false,
        )
    }

    private fun realPlayInvite(
        callId: String = "live-dialog@plat",
        fromTag: String = "plat-tag-xyz",
    ): SipRequest {
        val sdp = """
            v=0
            o=server 0 0 IN IP4 $platformIp
            s=Play
            c=IN IP4 $platformIp
            t=0 0
            m=video 30000 RTP/AVP 96
            a=recvonly
            a=rtpmap:96 PS/90000
            y=0100000001
        """.trimIndent().replace("\n", "\r\n")
        return SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:$channelId@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:8160;branch=z9hG4bK-inv"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformServerId@$platformDomain>;tag=$fromTag"),
                SipMessage.Header(SipHeader.TO, "<sip:$channelId@$platformDomain>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
                SipMessage.Header(SipHeader.CONTACT, "<sip:$platformServerId@$platformIp:8160>"),
                SipMessage.Header("Content-Type", "application/sdp"),
            ),
            body = sdp.encodeToByteArray(),
        )
    }

    private fun midDialogBye(callId: String, fromTag: String): SipRequest =
        SipRequest(
            method = SipMethod.BYE,
            requestUri = "sip:$channelId@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:8160;branch=z9hG4bK-bye"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformServerId@$platformDomain>;tag=$fromTag"),
                SipMessage.Header(SipHeader.TO, "<sip:$channelId@$platformDomain>;tag=device"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "2 BYE"),
            ),
            body = ByteArray(0),
        )

    private fun midDialogCancel(callId: String, fromTag: String): SipRequest =
        SipRequest(
            method = SipMethod.CANCEL,
            requestUri = "sip:$channelId@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:8160;branch=z9hG4bK-cancel"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformServerId@$platformDomain>;tag=$fromTag"),
                SipMessage.Header(SipHeader.TO, "<sip:$channelId@$platformDomain>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 CANCEL"),
            ),
            body = ByteArray(0),
        )

    /** activeStream 建立后取出 verifier 关注的状态:callId / 来源 IP。 */
    private suspend fun establishStream(
        invite: InviteCoordinatorImpl,
        transport: MockSipTransport,
        callId: String,
        fromTag: String,
    ) {
        invite.onIncoming(realPlayInvite(callId = callId, fromTag = fromTag).asEnvelope(sourceIp = platformIp))
    }

    // ---------------------------- BYE ----------------------------

    /** Call-ID + remoteTag + sourceIp 全对 → 200 OK + active 清理。 */
    @Test fun mid_dialog_bye_legit_passes() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val invite = newInvite(this, transport)

        val callId = "live-1@plat"
        val fromTag = "legit-tag"
        establishStream(invite, transport, callId, fromTag)
        runCurrent()
        // 应有 200 OK INVITE 响应,记下 toTag(虽然 verifier 不强校验 localTag,但完整流程需要)
        val invite200 = transport.sent.filterIsInstance<SipResponse>().firstOrNull { it.statusCode == 200 }
        assertNotNull(invite200, "INVITE 200 OK 应已发出")
        transport.sent.clear()

        val result = invite.onIncoming(
            midDialogBye(callId, fromTag).asEnvelope(sourceIp = platformIp)
        )
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp, "合法 BYE 应有响应")
        assertEquals(200, resp.statusCode, "合法 mid-dialog BYE 应返 200 OK")
        assertNull(invite.activeStreamSnapshot.value, "BYE 后 activeStream 应被清理")
        assertEquals(InviteState.Idle, invite.state.value)
    }

    /** Call-ID 对但 remoteTag 错(LAN 抓包伪造)→ 481。 */
    @Test fun mid_dialog_bye_wrong_remote_tag_returns_481() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val invite = newInvite(this, transport)

        val callId = "live-2@plat"
        establishStream(invite, transport, callId, fromTag = "legit-tag")
        runCurrent()
        transport.sent.clear()

        val result = invite.onIncoming(
            midDialogBye(callId, fromTag = "evil-tag").asEnvelope(sourceIp = platformIp)
        )
        runCurrent()
        assertEquals(RoutingResult.Handled, result, "verifier 应吃下并返 481")
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp, "应有响应")
        assertEquals(481, resp.statusCode, "P1-3:BYE remoteTag 错 → 481")
        // 关键:伪造的 BYE 不应中断真实直播
        assertNotNull(invite.activeStreamSnapshot.value, "伪造 BYE 不应中断 activeStream")
        assertEquals(InviteState.Streaming, invite.state.value)
        invite.shutdown()
    }

    /** Call-ID + remoteTag 都对,但 sourceIp 错(攻击者从非授权 IP)→ 481。 */
    @Test fun mid_dialog_bye_wrong_source_ip_returns_481() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val invite = newInvite(this, transport)

        val callId = "live-3@plat"
        val fromTag = "legit-tag"
        establishStream(invite, transport, callId, fromTag)
        runCurrent()
        transport.sent.clear()

        val result = invite.onIncoming(
            midDialogBye(callId, fromTag).asEnvelope(sourceIp = "10.99.99.99")
        )
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp)
        assertEquals(481, resp.statusCode, "P1-3:BYE sourceIp 错 → 481")
        assertNotNull(invite.activeStreamSnapshot.value, "伪造来源 BYE 不应中断 activeStream")
        invite.shutdown()
    }

    // ---------------------------- CANCEL ----------------------------

    /** Call-ID 对但 remoteTag 错 → CANCEL 481,不应停流。 */
    @Test fun mid_dialog_cancel_wrong_remote_tag_returns_481() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val invite = newInvite(this, transport)

        val callId = "live-4@plat"
        establishStream(invite, transport, callId, fromTag = "legit-tag")
        runCurrent()
        transport.sent.clear()

        val result = invite.onIncoming(
            midDialogCancel(callId, fromTag = "evil-tag").asEnvelope(sourceIp = platformIp)
        )
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp, "应有响应")
        assertEquals(481, resp.statusCode, "P1-3:CANCEL remoteTag 错 → 481")
        // CANCEL 伪造不应中断直播
        assertNotNull(invite.activeStreamSnapshot.value, "伪造 CANCEL 不应中断 activeStream")
        invite.shutdown()
    }

    /** Call-ID + remoteTag 对,sourceIp 错 → CANCEL 481。 */
    @Test fun mid_dialog_cancel_wrong_source_ip_returns_481() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val invite = newInvite(this, transport)

        val callId = "live-5@plat"
        val fromTag = "legit-tag"
        establishStream(invite, transport, callId, fromTag)
        runCurrent()
        transport.sent.clear()

        val result = invite.onIncoming(
            midDialogCancel(callId, fromTag).asEnvelope(sourceIp = "10.99.99.99")
        )
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp)
        assertEquals(481, resp.statusCode, "P1-3:CANCEL sourceIp 错 → 481")
        assertNotNull(invite.activeStreamSnapshot.value, "伪造来源 CANCEL 不应中断 activeStream")
        invite.shutdown()
    }

    /** 合法 CANCEL → 200 OK + 停流。 */
    @Test fun mid_dialog_cancel_legit_passes() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val invite = newInvite(this, transport)

        val callId = "live-6@plat"
        val fromTag = "legit"
        establishStream(invite, transport, callId, fromTag)
        runCurrent()
        transport.sent.clear()

        val result = invite.onIncoming(
            midDialogCancel(callId, fromTag).asEnvelope(sourceIp = platformIp)
        )
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp)
        assertEquals(200, resp.statusCode, "合法 mid-dialog CANCEL 应返 200 OK")
        assertNull(invite.activeStreamSnapshot.value, "合法 CANCEL 后 activeStream 应被清理")
    }
}

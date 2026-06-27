package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.domain.PlaybackBuilder
import com.uvp.sim.domain.PlaybackSession
import com.uvp.sim.network.TransportType
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordType
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.recording.RecordingState
import com.uvp.sim.sip.PlaybackOffer
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.testing.asEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wave 7B P1-4:[PlaybackCoordinatorImpl] mid-dialog 请求 dialog identity 校验。
 *
 * codex 第二轮 audit 引用:
 *   "Playback INFO 校验仅看 Call-ID,缺 fromTag/toTag/remoteUri 校验"
 *
 * 行为契约(参考 RFC 3261 § 12 + GB28181):
 *  - 建立 INVITE 200 → activePlayback 记录 callId / localTag / remoteTag / remoteSourceIp
 *  - mid-dialog INFO / BYE 进来:
 *     · callId + remoteTag + sourceIp 全对 → 处理
 *     · 任一不匹配 → 481 Call/Transaction Does Not Exist(标准 SIP 响应)+ Warning log
 *
 * 攻击场景覆盖:
 *  - LAN 抓包拿到 Call-ID,攻击者发 INFO TEARDOWN 强制结束回放(remoteTag 错)
 *  - 攻击者从非授权 IP 发 BYE(sourceIp 错)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackDialogIdentityTest {

    private val platformIp = "192.168.10.222"
    private val platformDomain = "3502000000"
    private val platformServerId = "35020000002000000001"

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = platformIp, port = 8160,
            serverId = platformServerId, domain = platformDomain,
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

    private class FakeRecordingService(files: List<RecordingFile>) : RecordingService {
        override val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val files: StateFlow<List<RecordingFile>> = MutableStateFlow(files)
        override suspend fun start(source: RecordSource, channelId: String): Result<Unit> = Result.success(Unit)
        override suspend fun stop(): Result<RecordingFile?> = Result.success(null)
        override suspend fun load() = Unit
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
    }

    private fun newPb(
        scope: CoroutineScope,
        transport: MockSipTransport,
    ): PlaybackCoordinatorImpl {
        val recordingFile = RecordingFile(
            id = "a", startTimeMs = 1_700_000_000_000L, endTimeMs = 1_700_001_000_000L,
            durationMs = 1_000_000L, channelId = "35020000001320000001",
            filePath = "/r/a.mp4", sizeBytes = 1024L, type = RecordType.Time,
        )
        val builder = object : PlaybackBuilder {
            override suspend fun build(offer: PlaybackOffer, segments: List<RecordingFile>, ssrc: String): PlaybackSession =
                object : PlaybackSession {
                    override val localRtpPort: Int = 12345
                    /** suspend 一辈子,模拟 playback.run() 持续推流(否则 finally 会清掉 activePlayback)。 */
                    override suspend fun run() {
                        CompletableDeferred<Unit>().await()
                    }
                    override suspend fun cancel() = Unit
                }
        }
        return PlaybackCoordinatorImpl(
            config = config(),
            transport = transport,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            scope = scope,
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
            playbackBuilder = builder,
            recordingService = FakeRecordingService(listOf(recordingFile)),
        )
    }

    private fun playbackInvite(
        callId: String = "pb-dialog@plat",
        fromTag: String = "plat-tag-xyz",
        channelId: String = "35020000001320000001",
    ): SipRequest {
        // PlaybackOffer 解析参考 SdpPlaybackParser:s=Playback + u=channel:0 + t=start end
        val sdp = """
            v=0
            o=server 0 0 IN IP4 $platformIp
            s=Playback
            u=$channelId:0
            c=IN IP4 $platformIp
            t=1700000000 1700001000
            m=video 30000 RTP/AVP 96
            a=recvonly
            a=rtpmap:96 PS/90000
            y=0100000001
        """.trimIndent().replace("\n", "\r\n")
        return SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:$channelId@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:8160;branch=z9hG4bK-pb"),
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

    private fun midDialogInfo(callId: String, fromTag: String, mansRtsp: String = "PLAY rtsp://x RTSP/1.0\nCSeq: 1\nRange: npt=15.0-\n\n"): SipRequest =
        SipRequest(
            method = SipMethod.INFO,
            requestUri = "sip:35020000001320000001@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:8160;branch=z9hG4bK-info"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformServerId@$platformDomain>;tag=$fromTag"),
                SipMessage.Header(SipHeader.TO, "<sip:35020000001320000001@$platformDomain>;tag=device"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "2 INFO"),
                SipMessage.Header("Content-Type", "application/MANSRTSP"),
            ),
            body = mansRtsp.encodeToByteArray(),
        )

    private fun midDialogBye(callId: String, fromTag: String): SipRequest =
        SipRequest(
            method = SipMethod.BYE,
            requestUri = "sip:35020000001320000001@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:8160;branch=z9hG4bK-bye"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformServerId@$platformDomain>;tag=$fromTag"),
                SipMessage.Header(SipHeader.TO, "<sip:35020000001320000001@$platformDomain>;tag=device"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "3 BYE"),
            ),
            body = ByteArray(0),
        )

    /** 建立 dialog 后,合法 mid-dialog 请求应通过(callId / remoteTag / sourceIp 全对)。 */
    @Test fun mid_dialog_info_legit_dialog_passes() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val pb = newPb(this, transport)

        val callId = "pb-1@plat"
        val fromTag = "plat-tag-1"
        pb.onIncoming(playbackInvite(callId = callId, fromTag = fromTag).asEnvelope(sourceIp = platformIp))
        runCurrent()
        transport.sent.clear()

        val result = pb.onIncoming(midDialogInfo(callId, fromTag).asEnvelope(sourceIp = platformIp))
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        // 合法 INFO → 200 OK(不是 481)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp, "应有响应")
        assertEquals(200, resp.statusCode, "合法 mid-dialog INFO 应返回 200")
        pb.shutdown()
    }

    /** Call-ID 正确但 remoteTag 错(攻击者 LAN 抓 Call-ID 后伪造 fromTag)→ 481。 */
    @Test fun mid_dialog_info_wrong_remote_tag_returns_481() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val pb = newPb(this, transport)

        val callId = "pb-2@plat"
        pb.onIncoming(playbackInvite(callId = callId, fromTag = "legit-tag").asEnvelope(sourceIp = platformIp))
        runCurrent()
        transport.sent.clear()

        // 攻击者伪造 fromTag
        val result = pb.onIncoming(midDialogInfo(callId, fromTag = "evil-tag").asEnvelope(sourceIp = platformIp))
        runCurrent()
        assertEquals(RoutingResult.Handled, result, "verifier 应吃下并返 481")
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp, "应有响应")
        assertEquals(481, resp.statusCode, "P1-4:remoteTag 错 → 481 Call/Transaction Does Not Exist")
        pb.shutdown()
    }

    /** Call-ID + remoteTag 都对,但 sourceIp 错(攻击者从非授权 IP 发请求)→ 481。 */
    @Test fun mid_dialog_info_wrong_source_ip_returns_481() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val pb = newPb(this, transport)

        val callId = "pb-3@plat"
        val fromTag = "legit-tag"
        pb.onIncoming(playbackInvite(callId = callId, fromTag = fromTag).asEnvelope(sourceIp = platformIp))
        runCurrent()
        transport.sent.clear()

        // 攻击者用对的 callId + fromTag(LAN 抓包),但来源 IP 不是建立 dialog 时的 IP
        val result = pb.onIncoming(midDialogInfo(callId, fromTag).asEnvelope(sourceIp = "10.99.99.99"))
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp)
        assertEquals(481, resp.statusCode, "P1-4:sourceIp 错 → 481")
        pb.shutdown()
    }

    /** Call-ID 错 → INFO 进 verifier(activePlayback != null)→ MismatchCallId → 481。 */
    @Test fun mid_dialog_info_wrong_call_id_returns_481() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val pb = newPb(this, transport)

        val callId = "pb-4@plat"
        pb.onIncoming(playbackInvite(callId = callId, fromTag = "tag-4").asEnvelope(sourceIp = platformIp))
        runCurrent()
        transport.sent.clear()

        val result = pb.onIncoming(midDialogInfo("evil-call-id", "any").asEnvelope(sourceIp = platformIp))
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp)
        assertEquals(481, resp.statusCode, "P1-4:Call-ID 错 → 481")
        pb.shutdown()
    }

    /** mid-dialog BYE 同款校验。Call-ID 对得上才进 verifier,否则 Skip 留给 InviteCoord。 */
    @Test fun mid_dialog_bye_wrong_remote_tag_returns_481() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val pb = newPb(this, transport)

        val callId = "pb-5@plat"
        pb.onIncoming(playbackInvite(callId = callId, fromTag = "legit").asEnvelope(sourceIp = platformIp))
        runCurrent()
        transport.sent.clear()

        val result = pb.onIncoming(midDialogBye(callId, fromTag = "evil-tag").asEnvelope(sourceIp = platformIp))
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp)
        assertEquals(481, resp.statusCode, "P1-4:BYE remoteTag 错 → 481")
        pb.shutdown()
    }

    /** 合法 BYE → 200,且 active playback 应被清理。 */
    @Test fun mid_dialog_bye_legit_dialog_passes() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val pb = newPb(this, transport)

        val callId = "pb-6@plat"
        val fromTag = "legit"
        pb.onIncoming(playbackInvite(callId = callId, fromTag = fromTag).asEnvelope(sourceIp = platformIp))
        runCurrent()
        transport.sent.clear()

        val result = pb.onIncoming(midDialogBye(callId, fromTag).asEnvelope(sourceIp = platformIp))
        runCurrent()
        assertEquals(RoutingResult.Handled, result)
        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp)
        assertEquals(200, resp.statusCode, "合法 mid-dialog BYE 应返 200 OK")
        assertEquals(PlaybackState.Idle, pb.state.value, "BYE 后应回 Idle")
        // 不调 shutdown,因为 BYE 应该已经 cancel 了 playbackJob
    }
}

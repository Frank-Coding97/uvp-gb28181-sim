package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipEvent
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipHeaderHelpers
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import com.uvp.sim.sip.SipStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * [InviteCoordinator] 真实现(PR4 T4.2 GREEN)。
 *
 * 接管 Engine 上 INVITE / ACK / BYE / CANCEL / INFO(MANSRTSP)+ 直播 / 回放推流 +
 * 反向广播 INVITE handshake,共 ~28 个符号 / ~1100 行迁移。
 *
 * 跨域决策(plans/refactor-pr4-invite-coordinator.md):
 *   - 决策 1:实现 [BroadcastInvoker.fireBroadcastInvite],ManscdpRouter 直接注入本类
 *   - 决策 2:Engine.unregister 直接调 invite.stopStream
 *   - 决策 3:Play vs Playback 二分留 Invite 内部(PR5 split 给 PlaybackCoordinator)
 *   - 决策 4:InviteEvent 13 类,Engine 桥接翻译 SimEvent
 *   - 决策 6:RX 媒体链留 Engine,通过 [BroadcastDialogHandshakeListener] 临时桥反向调
 *
 * **守约**:严格按 Engine 现有 early-return 顺序复制,SDP 协商 / SSRC 生成 / RTCP 字段 1:1。
 *
 * 跟 PR2 / PR3 一样,SN 池 / dialog identity 通过 6 个 lambda 跨域共享:
 * cseq / callId / fromTag 三件套 78/85/47 处既有引用通过 Engine 注入读写直达全局池。
 */
internal class InviteCoordinatorImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val outbox: com.uvp.sim.sip.SipOutbox,
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val cameraCapture: com.uvp.sim.camera.CameraCapture? = null,
    private val audioCapture: com.uvp.sim.camera.AudioCapture? = null,
    private val rtpSenderFactory: ((host: String, port: Int, mode: RtpMode, expectedClientHost: String?) -> RtpSender)? = null,
    private val catalogTree: StateFlow<List<CatalogNode>> = MutableStateFlow(emptyList()),
    private val clockOffsetProvider: () -> ClockOffset = { ClockOffset.Empty },
    private val mutableSipState: MutableStateFlow<SipState> = MutableStateFlow(SipState.Disconnected),
    private val simEventEmit: suspend (SimEvent) -> Unit = {},
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
) : InviteCoordinator {

    // ---- 公开 state / events ----
    private val _state = MutableStateFlow(InviteState.Idle)
    override val state: StateFlow<InviteState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<InviteEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<InviteEvent> = _events.asSharedFlow()

    private val _activeStreamSnapshot = MutableStateFlow<ActiveStreamSnapshot?>(null)
    override val activeStreamSnapshot: StateFlow<ActiveStreamSnapshot?> =
        _activeStreamSnapshot.asStateFlow()

    private val _currentChannelName = MutableStateFlow(config.device.videoChannelName)
    override val currentChannelName: StateFlow<String> = _currentChannelName.asStateFlow()

    // ---- SN 池 provider 适配(跟 PR2 / PR3 同模式) ----
    private var internalCseq: Int = 0
    private var internalCallId: String? = null
    private var internalFromTag: String? = null
    private val cseqRead: () -> Int = cseqProvider ?: { internalCseq }
    private val cseqIncAndRead: () -> Int = cseqIncrementer ?: { internalCseq += 1; internalCseq }
    private val callIdRead: () -> String? = callIdProvider ?: { internalCallId }
    private val callIdWrite: (String) -> Unit = callIdSetter ?: { internalCallId = it }
    private val fromTagRead: () -> String? = fromTagProvider ?: { internalFromTag }
    private val fromTagWrite: (String) -> Unit = fromTagSetter ?: { internalFromTag = it }
    private val cseq: Int get() = cseqRead()
    private fun cseqInc(): Int = cseqIncAndRead()

    private val localIp: String get() = localIpProvider()

    // ---- 内部状态(从 Engine 搬过来) ----
    private val mutex = Mutex()
    private var ackTimeoutJob: Job? = null
    private var awaitingAckCallId: String? = null
    private var activeStream: ActiveStream? = null
    // R1 #3:并发 INVITE 竞态守卫。
    // 接受路径从"过滤通过"到"activeStream 赋值"之间没有锁,两路并发 INVITE 都能通过
    // `activeStream == null` 检查并各开一路 RTP / 200 OK。用 mutex 原子检查
    // (activeStream || acceptInFlight),第二路立刻 486 Busy。
    private var acceptInFlight: Boolean = false

    private data class ActiveStream(
        val callId: String,
        val ssrc: String,
        val rtpSender: RtpSender,
        val streamJob: Job,
        val audioJob: Job? = null,
        val statsJob: Job? = null,
        val rtcpSender: RtpSender? = null,
        val rtcpJob: Job? = null,
        val rtpTimestampProvider: () -> Long = { 0L },
        var frameCount: Int = 0,
        var packetCount: Int = 0,
        var octetCount: Long = 0L,
        var lastRtpTimestamp: Long = 0L,
        val localUri: String = "",
        val localTag: String = "",
        val remoteUri: String = "",
        val remoteTag: String = "",
        val remoteTarget: String = "",
        val channelId: String = "",
        val remoteHost: String = "",
        val remotePort: Int = 0,
        // P1-3(Wave 7B):dialog identity 四元组,mid-dialog 请求校验用。
        // 跟 PlaybackCoordinatorImpl.ActivePlayback 同款 — 建立 INVITE 200 时记录 envelope.sourceIp,
        // 后续 ACK / CANCEL / BYE 必须从同一来源进入并带正确 remoteTag,否则 RFC 3261 § 12.2.1.1 → 481。
        val remoteSourceIp: String? = null,
    )

    /**
     * P1-3 helper:把 [ActiveStream] 抽成 [com.uvp.sim.sip.DialogIdentityVerifier.DialogId]。
     */
    private fun ActiveStream.toDialogId(): com.uvp.sim.sip.DialogIdentityVerifier.DialogId =
        com.uvp.sim.sip.DialogIdentityVerifier.DialogId(
            callId = callId,
            localTag = localTag,
            remoteTag = remoteTag,
            remoteSourceIp = remoteSourceIp,
        )

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        const val ACK_TIMEOUT_MS: Long = 32_000L
        const val MEDIA_STATS_INTERVAL_MS: Long = 30_000L
        const val RTCP_SR_INTERVAL_MS: Long = 5_000L
    }

    // ---- onIncoming 总分发(T4.2 实装,T4.1 stub 已删) ----
    override suspend fun onIncoming(envelope: com.uvp.sim.network.SipEnvelope): RoutingResult {
        val msg = envelope.message
        return when (msg) {
            is SipResponse -> handleResponse(msg)
            is SipRequest -> handleRequest(envelope, msg)
        }
    }

    override suspend fun shutdown() {
        // R1 #4 (verify-2 follow-up):跟 stopActiveStream 共享 mutex 防双进。
        val active = mutex.withLock {
            val cur = activeStream
            activeStream = null
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            awaitingAckCallId = null
            cur
        }
        active?.let {
            it.statsJob?.cancel()
            it.streamJob.cancel()
            it.audioJob?.cancel()
            it.rtcpJob?.cancel()
            try { it.rtpSender.close() } catch (_: Throwable) {}
            try { it.rtcpSender?.close() } catch (_: Throwable) {}
        }
    }

    // ---- PR5 T5.4:Invite 退出广播域 + 回放域,onIncoming 只处理 INVITE / ACK / BYE / CANCEL ----
    // 真实方法体由后续 Edit 注入,这里先放占位 stub 防编译破
    private suspend fun handleResponse(resp: SipResponse): RoutingResult {
        // PR5 T5.4:广播 INVITE 响应归 BroadcastCoordinator,Invite 不再吃响应
        return RoutingResult.Skip
    }

    private suspend fun handleRequest(envelope: com.uvp.sim.network.SipEnvelope, req: SipRequest): RoutingResult {
        return when (req.method) {
            // PR5 T5.4:Invite 处理 INVITE,内部 SDP isPlayback 时 Skip 让 Engine 路由给 Playback
            SipMethod.INVITE -> handleInviteMaybe(envelope, req)
            SipMethod.ACK -> { handleAck(envelope, req); RoutingResult.Handled }
            SipMethod.BYE -> {
                val cid = req.callId() ?: ""
                val active = activeStream
                if (active != null && active.callId == cid) {
                    handleBye(envelope, req); RoutingResult.Handled
                } else RoutingResult.Skip
            }
            SipMethod.CANCEL -> { handleCancel(envelope, req); RoutingResult.Handled }
            // INFO 全部 Skip(回放归 Playback,MANSCDP 归 Mans)
            SipMethod.INFO -> RoutingResult.Skip
            else -> RoutingResult.Skip
        }
    }

    /**
     * INVITE 路由:SDP probe 后 Playback 类 Skip 给 Engine 路由 PlaybackCoordinator,
     * 直播路径在本类完成。
     *
     * H-1 + P0-2:在路由前先校验 envelope.sourceIp + From host,LAN 内任何攻击者
     * 伪造的 INVITE 直接 403 拒绝,不进入后续 SDP 解析 / 推流流程。
     *
     * Wave 7B P0-2:guard 改走共享 [PlatformAuthorizer],跟 Playback 复用同一份判定。
     */
    private suspend fun handleInviteMaybe(envelope: com.uvp.sim.network.SipEnvelope, req: SipRequest): RoutingResult {
        if (!com.uvp.sim.sip.PlatformAuthorizer.isInviteFromAuthorizedPlatform(envelope, config)) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "拒绝 INVITE: 未授权来源(sourceIp=${envelope.sourceIp}, expected domain=${config.server.domain}) → 403"
            )
            sendSimpleResponse(req, statusCode = 403, reasonPhrase = "Forbidden")
            return RoutingResult.Handled
        }
        val isPlayback = try {
            com.uvp.sim.sip.SdpPlaybackParser.parse(req.body).isPlayback
        } catch (_: Throwable) {
            // cross-review R1 #5:SDP 解析失败过去 `catch { false }` 把畸形 INVITE 当 live 处理 →
            // 路由边界塌陷(畸形输入静默走直播分支,不回任何 SIP 错误)。
            // fail-closed:解析失败回 400 Bad Request 并 Handled,不进任何媒体路径。
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "拒绝 INVITE: SDP 解析失败(sourceIp=${envelope.sourceIp}) → 400"
            )
            sendSimpleResponse(req, statusCode = 400, reasonPhrase = "Bad Request")
            return RoutingResult.Handled
        }
        return if (isPlayback) RoutingResult.Skip
        else { handleInvite(envelope, req); RoutingResult.Handled }
    }

    // ----------------------------------------------------------------
    // 通用 helper(InviteCoord 独有的 parseUriHost 留在本类,其它都走 SipHeaderHelpers)
    // ----------------------------------------------------------------

    /** 从 `sip:user@host[:port][;params]` 提取 host 段(不含 port / params)。 */
    private fun parseUriHost(uri: String): String {
        val afterAt = uri.substringAfter("sip:", uri).substringAfter('@', "")
        return afterAt.substringBefore(':').substringBefore(';').substringBefore('>').trim()
    }

    // H-1 / P0-2 来源校验已抽到 com.uvp.sim.sip.PlatformAuthorizer,本类不再持有私有 helper。

    private fun extractInviteTarget(invite: SipRequest): String {
        val ru = invite.requestUri
        val sipBody = ru.substringAfter("sip:", "").substringAfter("sips:", ru.substringAfter("sip:", ""))
        val userHost = if (sipBody.isNotEmpty()) sipBody else ""
        val user = userHost.substringBefore('@', "").substringBefore(';').trim()
        if (user.isNotEmpty()) return user
        val to = invite.toHeader() ?: return ""
        return SipHeaderHelpers.parseUri(to).substringAfter("sip:", "")
            .substringBefore('@', "")
            .substringBefore(';')
            .trim()
    }

    private fun classifyInviteTarget(channelId: String): Pair<Int, String>? {
        if (channelId.isBlank()) return null
        val node = catalogTree.value.firstOrNull { it.id == channelId } ?: return null
        return when (node.type) {
            CatalogNodeType.VideoChannel -> null
            CatalogNodeType.AlarmChannel ->
                488 to "Not Acceptable Here (alarm channel does not stream)"
            CatalogNodeType.Device ->
                488 to "Not Acceptable Here (cannot invite device root)"
            CatalogNodeType.BusinessGroup ->
                488 to "Not Acceptable Here (cannot invite business group)"
            CatalogNodeType.VirtualOrg ->
                488 to "Not Acceptable Here (cannot invite virtual org)"
        }
    }

    private suspend fun sendSimpleResponse(req: SipRequest, statusCode: Int, reasonPhrase: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req,
                statusCode = statusCode,
                reasonPhrase = reasonPhrase,
                toTag = SipBuilders.randomTag(),
            )
            outbox.send(resp).getOrThrow()
        }
    }

    private suspend fun sendBusyResponse(req: SipRequest, reason: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req, statusCode = 486, reasonPhrase = "Busy Here",
                toTag = SipBuilders.randomTag(),
                userAgent = config.userAgent,
            )
            outbox.send(resp).getOrThrow()
        }
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "拒绝 INVITE → 486 ($reason)")
    }

    private suspend fun sendNotFoundResponse(req: SipRequest, reason: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req, statusCode = 487, reasonPhrase = "Request Terminated",
                toTag = SipBuilders.randomTag(),
                userAgent = config.userAgent,
            )
            outbox.send(resp).getOrThrow()
        }
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "拒绝 INVITE → 487 ($reason)")
    }

    // ----------------------------------------------------------------
    // 占位:大块业务 / broadcast / playback 由后续 Edit 注入
    // ----------------------------------------------------------------
    private suspend fun handleInvite(envelope: com.uvp.sim.network.SipEnvelope, invite: SipRequest) {
        // 按 channelId 类型路由 — 不支持的类型立即 488
        val channelId = extractInviteTarget(invite)
        val rejection = classifyInviteTarget(channelId)
        if (rejection != null) {
            try {
                val resp = SipBuilders.buildSimpleError(
                    request = invite,
                    statusCode = rejection.first,
                    reasonPhrase = rejection.second,
                    toTag = SipBuilders.randomTag(),
                )
                outbox.send(resp).getOrThrow()
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "拒绝 INVITE: channelId=$channelId → ${rejection.first} ${rejection.second}"
                )
            } catch (e: Throwable) {
                simEventEmit(com.uvp.sim.domain.transportErrorOf("send INVITE reject", e))
            }
            return
        }

        // PR5 T5.4:SDP isPlayback 分流已在 handleInviteMaybe 完成,这里只处理 Play 路径

        // R1 #3:已有活跃流 || 接受中 → 486,两个状态用同一把 mutex 原子检查 + 占用,
        // 防止两路并发 INVITE 同时通过"activeStream == null"检查后各开一路。
        val acquired = mutex.withLock {
            if (activeStream != null || acceptInFlight) {
                false
            } else {
                acceptInFlight = true
                true
            }
        }
        if (!acquired) {
            sendBusyResponse(invite, "已有直播流推送中,拒绝并发第二路")
            return
        }

        try {
            doAcceptInvite(envelope, invite, channelId)
        } finally {
            mutex.withLock { acceptInFlight = false }
        }
    }

    private suspend fun doAcceptInvite(
        envelope: com.uvp.sim.network.SipEnvelope,
        invite: SipRequest,
        channelId: String,
    ) {
        mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.InviteReceived)
        val cid = invite.callId() ?: ""
        simEventEmit(SimEvent.IncomingInvite(cid))

        val sender = rtpSenderFactory
        val cam = cameraCapture
        if (sender == null || cam == null) return  // 单测路径,无媒体管线

        cam.setFacing(config.device.facingForChannel(channelId))
        _currentChannelName.value = config.device.channelNameForChannel(channelId)

        val offer = try {
            com.uvp.sim.sip.SdpParser.parseOffer(invite.body)
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("SDP parse", e))
            return
        }

        val ssrc = offer.ssrc ?: com.uvp.sim.sip.SsrcUtils.generate(
            realtime = true,
            domainCode = config.server.domain.takeLast(5).padStart(5, '0'),
            sequence = (cseq + 1) and 0x0FFF,
        )

        val rtpMode = when (offer.transport) {
            com.uvp.sim.sip.SdpTransport.UDP -> RtpMode.UDP
            com.uvp.sim.sip.SdpTransport.TCP -> when (offer.tcpSetup) {
                com.uvp.sim.sip.SdpTcpSetup.PASSIVE -> RtpMode.TCP_ACTIVE
                com.uvp.sim.sip.SdpTcpSetup.ACTIVE -> RtpMode.TCP_PASSIVE
                com.uvp.sim.sip.SdpTcpSetup.ACTPASS -> RtpMode.TCP_ACTIVE
            }
        }

        // P1-5: TCP_PASSIVE 模式下 expectedClientHost = SDP remote IP(平台真实端点),
        // UDP/TCP_ACTIVE 不需要验证(null)。
        val expectedClientHost = if (rtpMode == RtpMode.TCP_PASSIVE) offer.remoteIp else null
        val rtp = sender(offer.remoteIp, offer.remotePort, rtpMode, expectedClientHost)
        val localRtpPort = try {
            rtp.bindLocalPort()
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("RTP bind", e))
            return
        }

        val sdpAnswer = com.uvp.sim.sip.SdpAnswer.buildPlayAnswer(
            deviceId = config.device.deviceId,
            localIp = localIp,
            localRtpPort = localRtpPort,
            ssrc = ssrc,
            sessionName = "Play",
            transport = offer.transport,
            tcpSetup = offer.tcpSetup,
            mediaSpec = SipHeaderHelpers.buildSdpMediaSpec(config),
        )
        val deviceContact = "<sip:${config.device.deviceId}@$localIp:${localPortProvider()}>"
        val localToTag = SipBuilders.randomTag()
        val inviteFromUser = SipHeaderHelpers.parseUriUser(
            SipHeaderHelpers.parseUri(invite.fromHeader() ?: ""),
            fallback = config.server.serverId,
        )
        val response = SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = deviceContact,
            toTag = localToTag,
            sdpBody = sdpAnswer,
            userAgent = config.userAgent,
            subject = SipBuilders.subject(
                // R2 #7:Subject sender ID 应为通道编码(GB §20.4),
                // 之前用 device.deviceId 导致平台无法关联到真正出流的通道。
                senderId = channelId.ifBlank { config.device.deviceId },
                ssrc = ssrc,
                receiverId = inviteFromUser,
            ),
        )
        val inviteFromHeader = invite.fromHeader() ?: ""
        val inviteToHeader = invite.toHeader() ?: ""
        val inviteContact = invite.firstHeader(SipHeader.CONTACT) ?: ""
        val remoteUri = SipHeaderHelpers.parseUri(inviteFromHeader)
        val remoteTag = SipHeaderHelpers.parseTag(inviteFromHeader)
        val localUri = SipHeaderHelpers.parseUri(inviteToHeader)
        val remoteTarget = SipHeaderHelpers.parseUri(inviteContact).ifEmpty { remoteUri }
        try {
            outbox.send(response).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send 200 OK", e))
            try { rtp.close() } catch (_: Throwable) {}
            return
        }

        installAckWatchdog(cid)

        simEventEmit(SimEvent.StreamStarted(cid, offer.remoteIp, offer.remotePort, ssrc))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "开始推流 → ${offer.remoteIp}:${offer.remotePort} ssrc=$ssrc"
        )

        val media = launchMediaJobs(cid, rtp, offer, ssrc, cam)

        val statsJob = scope.launch {
            while (true) {
                delay(MEDIA_STATS_INTERVAL_MS)
                val a = activeStream ?: break
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "RTP 推送中: ${a.frameCount} 帧 / ${a.packetCount} 包"
                )
                simEventEmit(
                    SimEvent.StreamStats(
                        callId = a.callId,
                        frameCount = a.frameCount,
                        packetCount = a.packetCount,
                    )
                )
            }
        }

        activeStream = ActiveStream(
            callId = cid,
            ssrc = ssrc,
            rtpSender = rtp,
            streamJob = media.streamJob,
            audioJob = media.audioJob,
            rtcpSender = media.rtcpSender,
            rtcpJob = media.rtcpJob,
            localUri = localUri,
            localTag = localToTag,
            remoteUri = remoteUri,
            remoteTag = remoteTag,
            remoteTarget = remoteTarget,
            channelId = channelId,
            remoteHost = offer.remoteIp,
            remotePort = offer.remotePort,
            // P1-3:记录建立 dialog 时 envelope 真实来源 IP,后续 mid-dialog 校验。
            remoteSourceIp = envelope.sourceIp,
            statsJob = statsJob,
        )
        _activeStreamSnapshot.value = ActiveStreamSnapshot(
            callId = cid, channelId = channelId,
            remoteHost = offer.remoteIp, remotePort = offer.remotePort, ssrc = ssrc,
        )
        _state.value = InviteState.Streaming
    }

    /** [doAcceptInvite] 启动的媒体推流 job 集合(视频 / 音频 / RTCP)+ RTCP 发送器。 */
    private class MediaJobs(
        val streamJob: Job,
        val audioJob: Job?,
        val rtcpSender: RtpSender,
        val rtcpJob: Job,
    )

    /**
     * 启动视频 / 音频 / RTCP 推流 job —— 从 [doAcceptInvite] 抽出。
     *
     * 视频帧 / 音频帧经 [com.uvp.sim.media.PsMuxer] + [com.uvp.sim.media.RtpPacker]
     * 打包后走 [rtp] 发送,共享一把 [Mutex] 串行化(RTP 序列号 / 计数器)。RTCP SR
     * 每 [RTCP_SR_INTERVAL_MS] 反馈一次。推流统计累加进 [activeStream](由调用方在本
     * 方法返回后赋值,job 内通过 `activeStream?.let` 安全读)。
     */
    private suspend fun launchMediaJobs(
        cid: String,
        rtp: RtpSender,
        offer: com.uvp.sim.sip.SdpOffer,
        ssrc: String,
        cam: com.uvp.sim.camera.CameraCapture,
    ): MediaJobs {
        val packer = com.uvp.sim.media.RtpPacker(
            payloadType = 96,
            ssrc = com.uvp.sim.sip.SsrcUtils.toRtpInt(ssrc),
        )
        val muxer = com.uvp.sim.media.PsMuxer().apply {
            audioCodec = if (audioCapture != null) config.video.audioCodec else null
        }
        val rtpMutex = Mutex()

        val streamJob = scope.launch {
            try {
                cam.start().collect { frame ->
                    val ps = muxer.muxFrame(frame)
                    val timestamp90k = frame.timestampUs * 9 / 100
                    val packets = packer.packFrame(ps, timestamp90k)
                    rtpMutex.withLock {
                        for (p in packets) {
                            rtp.send(p)
                            activeStream?.let {
                                it.packetCount += 1
                                it.octetCount += (p.size - 12).coerceAtLeast(0).toLong()
                                it.lastRtpTimestamp = timestamp90k
                            }
                        }
                    }
                    activeStream?.let { it.frameCount += 1 }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                simEventEmit(com.uvp.sim.domain.transportErrorOf("RTP video send", e))
                scope.launch { stopActiveStream(cid, "video send failed: ${e::class.simpleName}") }
            }
        }

        val audioJob = audioCapture?.let { audio ->
            scope.launch {
                try {
                    audio.start().collect { aFrame ->
                        val ps = muxer.muxAudio(aFrame)
                        val timestamp90k = aFrame.timestampUs * 9 / 100
                        val packets = packer.packFrame(ps, timestamp90k)
                        rtpMutex.withLock {
                            for (p in packets) {
                                rtp.send(p)
                                activeStream?.let {
                                    it.packetCount += 1
                                    it.octetCount += (p.size - 12).coerceAtLeast(0).toLong()
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    simEventEmit(com.uvp.sim.domain.transportErrorOf("RTP audio send", e))
                    scope.launch { stopActiveStream(cid, "audio send failed: ${e::class.simpleName}") }
                }
            }
        }

        // RTCP SR 反馈
        val rtcp = rtpSenderFactory!!(offer.remoteIp, offer.remotePort + 1, RtpMode.UDP, null)
        try { rtcp.bindLocalPort() } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("RTCP bind", e))
        }
        val ssrcInt = com.uvp.sim.sip.SsrcUtils.toRtpInt(ssrc)
        val rtcpJob = scope.launch {
            while (true) {
                delay(RTCP_SR_INTERVAL_MS)
                val a = activeStream ?: break
                runCatching {
                    val sr = com.uvp.sim.rtp.RtcpSender.buildSR(
                        ssrc = ssrcInt,
                        ntpEpochMs = clockOffsetProvider().adjustedNowMs(),
                        rtpTimestamp = a.lastRtpTimestamp,
                        senderPacketCount = a.packetCount.toLong(),
                        senderOctetCount = a.octetCount,
                    )
                    rtcp.send(sr)
                }
            }
        }

        return MediaJobs(streamJob, audioJob, rtcp, rtcpJob)
    }


    /**
     * 5.14 ACK watchdog —— 从 [doAcceptInvite] 抽出。
     *
     * R1 #4:ACK 超时不能只发事件,媒体管线已开,必须 stopActiveStream 释放
     * RTP / RTCP / camera / audio,否则在永不 ACK 场景下推流持续 → 资源泄漏 + 假"已连接"。
     * R1 #4 (verify follow-up):awaitingAckCallId 检查与置空放进 mutex,
     * 避免与 handleAck 并发竞态(ACK 在超时回调判断后才到达 → 双进 cleanup)。
     */
    private fun installAckWatchdog(cid: String) {
        awaitingAckCallId = cid
        ackTimeoutJob?.cancel()
        ackTimeoutJob = scope.launch {
            delay(ACK_TIMEOUT_MS)
            val shouldFire = mutex.withLock {
                if (awaitingAckCallId == cid) {
                    awaitingAckCallId = null
                    true
                } else false
            }
            if (shouldFire) {
                simEventEmit(SimEvent.InviteAckTimeout(cid))
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Lifecycle,
                    "INVITE 200 OK 未收到 ACK (${ACK_TIMEOUT_MS / 1000}s) — 平台可能已断开,释放媒体管线"
                )
                stopActiveStream(cid, "ACK timeout (${ACK_TIMEOUT_MS / 1000}s)")
            }
        }
    }

    /**
     * P1-3:把 envelope 跟 activeStream dialog identity 比对(直播链路 mid-dialog 守卫)。
     *
     * 跟 [PlaybackCoordinatorImpl.verifyDialogOrReject] 同款语义:
     * - [com.uvp.sim.sip.DialogIdentityVerifier.VerifyResult.Match] → 返回 true
     * - 其它三种 mismatch → CANCEL/BYE 返 481 + Warning 日志,ACK 仅日志(无响应)
     *
     * @param respondOn481 ACK 没有响应(RFC 3261 § 17.1.1.3),传 false 跳过 481 发送。
     */
    private suspend fun verifyMidDialogOrReject(
        envelope: com.uvp.sim.network.SipEnvelope,
        req: SipRequest,
        active: ActiveStream,
        op: String,
        respondOn481: Boolean = true,
    ): Boolean {
        val result = com.uvp.sim.sip.DialogIdentityVerifier.verify(envelope, active.toDialogId())
        if (result == com.uvp.sim.sip.DialogIdentityVerifier.VerifyResult.Match) return true
        SystemLogger.emit(
            LogLevel.Warning, LogTag.Lifecycle,
            "拒绝 INVITE $op:dialog identity 不匹配($result) → ${if (respondOn481) "481" else "丢弃"} " +
                "[expected callId=${active.callId} remoteTag=${active.remoteTag} sourceIp=${active.remoteSourceIp}, " +
                "got callId=${req.callId()} sourceIp=${envelope.sourceIp}]",
        )
        if (respondOn481) {
            runCatching {
                val resp = SipBuilders.buildSimpleResponse(
                    req, statusCode = 481, reasonPhrase = "Call/Transaction Does Not Exist",
                    toTag = SipBuilders.randomTag(),
                    userAgent = config.userAgent,
                )
                outbox.send(resp).getOrThrow()
            }
        }
        return false
    }

    private suspend fun handleAck(envelope: com.uvp.sim.network.SipEnvelope, ack: SipRequest) {
        val cid = ack.callId() ?: return
        // P1-3:有活跃流时 ACK 必须通过 dialog identity 校验;ACK 无响应(§ 17.1.1.3),失败丢弃。
        val active = activeStream
        if (active != null && active.callId == cid) {
            if (!verifyMidDialogOrReject(envelope, ack, active, op = "ACK", respondOn481 = false)) {
                return
            }
        }
        // R1 #4 (verify follow-up):跟超时回调争抢同一份 ack 状态,放进 mutex。
        mutex.withLock {
            if (cid == awaitingAckCallId) {
                ackTimeoutJob?.cancel()
                ackTimeoutJob = null
                awaitingAckCallId = null
            }
        }
    }

    private suspend fun handleCancel(envelope: com.uvp.sim.network.SipEnvelope, cancel: SipRequest) {
        val cid = cancel.callId() ?: ""
        val active = activeStream
        // P1-3:Call-ID 对得上 activeStream 才进 verifier;Call-ID 不对沿用旧行为(发 200 但不动状态)
        if (active != null && active.callId == cid) {
            if (!verifyMidDialogOrReject(envelope, cancel, active, op = "CANCEL")) {
                return
            }
        }
        try {
            val ok = SipBuilders.buildSimple200(cancel, userAgent = config.userAgent)
            outbox.send(ok).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send CANCEL 200", e))
        }
        if (active != null && active.callId == cid) {
            stopActiveStream(cid, "remote CANCEL")
            if (mutableSipState.value == SipState.InCall) {
                mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.CallEnded)
            }
            simEventEmit(SimEvent.CallEnded(cid, "remote CANCEL"))
        }
    }

    private suspend fun handleBye(envelope: com.uvp.sim.network.SipEnvelope, bye: SipRequest) {
        val cid = bye.callId() ?: ""
        val active = activeStream
        // P1-3:进入本 handler 时 handleRequest 已经判过 active.callId == cid(否则 Skip),
        // 这里把 dialog identity 全维度校验补齐 — remoteTag / sourceIp 任一不匹配 → 481。
        if (active != null) {
            if (!verifyMidDialogOrReject(envelope, bye, active, op = "BYE")) {
                return
            }
        }
        // 先发 200 OK
        try {
            val ok = SipBuilders.buildSimple200(bye, userAgent = config.userAgent)
            outbox.send(ok).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send BYE 200", e))
        }
        // PR5 T5.4:回放 dialog BYE 由 PlaybackCoordinator 接,本类只处理 activeStream
        stopActiveStream(cid, "remote BYE")
        if (mutableSipState.value == SipState.InCall) {
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.ByeReceived)
        }
        simEventEmit(SimEvent.CallEnded(cid, "remote BYE"))
    }

    private suspend fun stopActiveStream(callId: String, reason: String) {
        // R1 #4 (verify follow-up):原子 swap 防双进 — 多路触发(timeout / BYE / CANCEL / stopStream)
        // 同时跑时,只有第一个抢到 active 的路径执行清理。
        val active = mutex.withLock {
            val cur = activeStream ?: return@withLock null
            activeStream = null
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            awaitingAckCallId = null
            cur
        } ?: return
        _activeStreamSnapshot.value = null
        active.streamJob.cancel()
        active.audioJob?.cancel()
        active.statsJob?.cancel()
        active.rtcpJob?.cancel()
        try { active.rtpSender.close() } catch (_: Throwable) {}
        try { active.rtcpSender?.close() } catch (_: Throwable) {}
        try { cameraCapture?.stop() } catch (_: Throwable) {}
        try { audioCapture?.stop() } catch (_: Throwable) {}
        simEventEmit(
            SimEvent.StreamStopped(
                callId = callId,
                frameCount = active.frameCount,
                packetCount = active.packetCount,
                reason = reason,
            )
        )
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "停止推流 ($reason): ${active.frameCount} 帧 / ${active.packetCount} 包"
        )
        _state.value = InviteState.Idle
    }

    override suspend fun stopStream(reason: String) {
        val active = activeStream ?: return
        if (active.remoteUri.isEmpty() || active.remoteTag.isEmpty()) {
            stopActiveStream(active.callId, reason)
            return
        }
        try {
            val cseqLocal = cseqInc()
            val branch = SipBuilders.randomBranch()
            val bye = SipBuilders.buildBye(
                config = config,
                callId = active.callId,
                cseq = cseqLocal,
                branch = branch,
                localUri = active.localUri,
                localTag = active.localTag,
                remoteUri = active.remoteUri,
                remoteTag = active.remoteTag,
                remoteTarget = active.remoteTarget,
                localIp = localIp,
                localPort = localPortProvider(),
            )
            outbox.send(bye).getOrThrow()
            SystemLogger.emit(
                LogLevel.Info, LogTag.Lifecycle,
                "主动 BYE 终止推流: $reason"
            )
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send BYE", e))
        }
        stopActiveStream(active.callId, reason)
        if (mutableSipState.value == SipState.InCall) {
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.CallEnded)
        }
    }
}


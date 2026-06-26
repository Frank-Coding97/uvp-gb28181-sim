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
    private val rtpSenderFactory: ((host: String, port: Int, mode: RtpMode) -> RtpSender)? = null,
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
    )

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        const val ACK_TIMEOUT_MS: Long = 32_000L
        const val MEDIA_STATS_INTERVAL_MS: Long = 30_000L
        const val RTCP_SR_INTERVAL_MS: Long = 5_000L
    }

    // ---- onIncoming 总分发(T4.2 实装,T4.1 stub 已删) ----
    override suspend fun onIncoming(msg: SipMessage): RoutingResult {
        return when (msg) {
            is SipResponse -> handleResponse(msg)
            is SipRequest -> handleRequest(msg)
        }
    }

    override suspend fun shutdown() {
        // 先停活跃流(锁外)
        activeStream?.let { active ->
            active.statsJob?.cancel()
            active.streamJob.cancel()
            active.audioJob?.cancel()
            active.rtcpJob?.cancel()
            try { active.rtpSender.close() } catch (_: Throwable) {}
            try { active.rtcpSender?.close() } catch (_: Throwable) {}
            activeStream = null
        }
        mutex.withLock {
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            awaitingAckCallId = null
        }
    }

    // ---- PR5 T5.4:Invite 退出广播域 + 回放域,onIncoming 只处理 INVITE / ACK / BYE / CANCEL ----
    // 真实方法体由后续 Edit 注入,这里先放占位 stub 防编译破
    private suspend fun handleResponse(resp: SipResponse): RoutingResult {
        // PR5 T5.4:广播 INVITE 响应归 BroadcastCoordinator,Invite 不再吃响应
        return RoutingResult.Skip
    }

    private suspend fun handleRequest(req: SipRequest): RoutingResult {
        return when (req.method) {
            // PR5 T5.4:Invite 处理 INVITE,内部 SDP isPlayback 时 Skip 让 Engine 路由给 Playback
            SipMethod.INVITE -> handleInviteMaybe(req)
            SipMethod.ACK -> { handleAck(req); RoutingResult.Handled }
            SipMethod.BYE -> {
                val cid = req.callId() ?: ""
                val active = activeStream
                if (active != null && active.callId == cid) {
                    handleBye(req); RoutingResult.Handled
                } else RoutingResult.Skip
            }
            SipMethod.CANCEL -> { handleCancel(req); RoutingResult.Handled }
            // INFO 全部 Skip(回放归 Playback,MANSCDP 归 Mans)
            SipMethod.INFO -> RoutingResult.Skip
            else -> RoutingResult.Skip
        }
    }

    /**
     * INVITE 路由:SDP probe 后 Playback 类 Skip 给 Engine 路由 PlaybackCoordinator,
     * 直播路径在本类完成。
     *
     * H-1:在路由前先校验 From 头,LAN 内任何攻击者伪造的 INVITE 直接 403 拒绝,不进入
     * 后续 SDP 解析 / 推流流程。
     */
    private suspend fun handleInviteMaybe(req: SipRequest): RoutingResult {
        if (!isInviteFromAuthorizedPlatform(req)) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "拒绝 INVITE: From host 未匹配 ${config.server.domain} → 403"
            )
            sendSimpleResponse(req, statusCode = 403, reasonPhrase = "Forbidden")
            return RoutingResult.Handled
        }
        val isPlayback = try {
            com.uvp.sim.sip.SdpPlaybackParser.parse(req.body).isPlayback
        } catch (_: Throwable) { false }
        return if (isPlayback) RoutingResult.Skip
        else { handleInvite(req); RoutingResult.Handled }
    }

    // ----------------------------------------------------------------
    // 通用 helper(InviteCoord 独有的 parseUriHost 留在本类,其它都走 SipHeaderHelpers)
    // ----------------------------------------------------------------

    /** 从 `sip:user@host[:port][;params]` 提取 host 段(不含 port / params)。 */
    private fun parseUriHost(uri: String): String {
        val afterAt = uri.substringAfter("sip:", uri).substringAfter('@', "")
        return afterAt.substringBefore(':').substringBefore(';').substringBefore('>').trim()
    }

    /**
     * H-1 (security-audit §2):INVITE 必须来自登记的平台。
     *
     * 校验 From 头里的 SIP URI host 段(SIP domain)等于 `config.server.domain`。
     * GB28181 平台 INVITE 的 From 永远是 `sip:<serverId>@<domain>[:<port>]`,
     * 任何 host 不匹配的 INVITE 都视为 LAN 内伪造,返回 403 Forbidden。
     *
     * 仅校验 host(域) — user 段在不同 GB 实现下可能是 serverId / 业务别名 /
     * "server" 等占位串,过严匹配会误杀合法平台。
     */
    private fun isInviteFromAuthorizedPlatform(invite: SipRequest): Boolean {
        val fromHeader = invite.fromHeader() ?: return false
        val fromUri = SipHeaderHelpers.parseUri(fromHeader)
        val fromHost = parseUriHost(fromUri)
        return fromHost == config.server.domain
    }

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
    private suspend fun handleInvite(invite: SipRequest) {
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
                simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("send INVITE reject", e)))
            }
            return
        }

        // PR5 T5.4:SDP isPlayback 分流已在 handleInviteMaybe 完成,这里只处理 Play 路径

        // 已有活跃流 → 486
        if (activeStream != null) {
            sendBusyResponse(invite, "已有直播流推送中,拒绝并发第二路")
            return
        }

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
            simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("SDP parse", e)))
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

        val rtp = sender(offer.remoteIp, offer.remotePort, rtpMode)
        val localRtpPort = try {
            rtp.bindLocalPort()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("RTP bind", e)))
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
                senderId = config.device.deviceId,
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
            simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("send 200 OK", e)))
            try { rtp.close() } catch (_: Throwable) {}
            return
        }

        // 5.14 ACK watchdog
        awaitingAckCallId = cid
        ackTimeoutJob?.cancel()
        ackTimeoutJob = scope.launch {
            delay(ACK_TIMEOUT_MS)
            if (awaitingAckCallId == cid) {
                simEventEmit(SimEvent.InviteAckTimeout(cid))
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Lifecycle,
                    "INVITE 200 OK 未收到 ACK (${ACK_TIMEOUT_MS / 1000}s) — 平台可能已断开"
                )
                awaitingAckCallId = null
            }
        }

        simEventEmit(SimEvent.StreamStarted(cid, offer.remoteIp, offer.remotePort, ssrc))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "开始推流 → ${offer.remoteIp}:${offer.remotePort} ssrc=$ssrc"
        )

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
                simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("RTP video send", e)))
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
                    simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("RTP audio send", e)))
                    scope.launch { stopActiveStream(cid, "audio send failed: ${e::class.simpleName}") }
                }
            }
        }

        // RTCP SR 反馈
        val rtcp = sender(offer.remoteIp, offer.remotePort + 1, RtpMode.UDP)
        try { rtcp.bindLocalPort() } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("RTCP bind", e)))
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

        activeStream = ActiveStream(
            callId = cid,
            ssrc = ssrc,
            rtpSender = rtp,
            streamJob = streamJob,
            audioJob = audioJob,
            rtcpSender = rtcp,
            rtcpJob = rtcpJob,
            localUri = localUri,
            localTag = localToTag,
            remoteUri = remoteUri,
            remoteTag = remoteTag,
            remoteTarget = remoteTarget,
            channelId = channelId,
            remoteHost = offer.remoteIp,
            remotePort = offer.remotePort,
            statsJob = scope.launch {
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
            },
        )
        _activeStreamSnapshot.value = ActiveStreamSnapshot(
            callId = cid, channelId = channelId,
            remoteHost = offer.remoteIp, remotePort = offer.remotePort, ssrc = ssrc,
        )
        _state.value = InviteState.Streaming
    }
    private suspend fun handleAck(ack: SipRequest) {
        val cid = ack.callId() ?: return
        if (cid == awaitingAckCallId) {
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            awaitingAckCallId = null
        }
    }

    private suspend fun handleCancel(cancel: SipRequest) {
        try {
            val ok = SipBuilders.buildSimple200(cancel, userAgent = config.userAgent)
            outbox.send(ok).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("send CANCEL 200", e)))
        }
        val cid = cancel.callId() ?: ""
        val active = activeStream
        if (active != null && active.callId == cid) {
            stopActiveStream(cid, "remote CANCEL")
            if (mutableSipState.value == SipState.InCall) {
                mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.CallEnded)
            }
            simEventEmit(SimEvent.CallEnded(cid, "remote CANCEL"))
        }
    }

    private suspend fun handleBye(bye: SipRequest) {
        val cid = bye.callId() ?: ""
        // 先发 200 OK
        try {
            val ok = SipBuilders.buildSimple200(bye, userAgent = config.userAgent)
            outbox.send(ok).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("send BYE 200", e)))
        }
        // PR5 T5.4:回放 dialog BYE 由 PlaybackCoordinator 接,本类只处理 activeStream
        stopActiveStream(cid, "remote BYE")
        if (mutableSipState.value == SipState.InCall) {
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.ByeReceived)
        }
        simEventEmit(SimEvent.CallEnded(cid, "remote BYE"))
    }

    private suspend fun stopActiveStream(callId: String, reason: String) {
        val active = activeStream ?: return
        activeStream = null
        _activeStreamSnapshot.value = null
        ackTimeoutJob?.cancel()
        ackTimeoutJob = null
        awaitingAckCallId = null
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
            simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("send BYE", e)))
        }
        stopActiveStream(active.callId, reason)
        if (mutableSipState.value == SipState.InCall) {
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.CallEnded)
        }
    }
}


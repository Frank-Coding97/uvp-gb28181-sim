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
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val cameraCapture: com.uvp.sim.camera.CameraCapture? = null,
    private val audioCapture: com.uvp.sim.camera.AudioCapture? = null,
    private val rtpSenderFactory: ((host: String, port: Int, mode: RtpMode) -> RtpSender)? = null,
    private val recordingService: RecordingService = com.uvp.sim.recording.NoopRecordingService,
    private val playbackBuilder: com.uvp.sim.domain.PlaybackBuilder? = null,
    private val catalogTree: StateFlow<List<CatalogNode>> = MutableStateFlow(emptyList()),
    private val clockOffsetProvider: () -> ClockOffset = { ClockOffset.Empty },
    private val broadcastHandshakeListener: BroadcastDialogHandshakeListener = NoopBroadcastDialogHandshakeListener,
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
    private var activePlayback: ActivePlayback? = null

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

    private data class ActivePlayback(
        val callId: String,
        val ssrc: String,
        val playbackJob: Job,
        val rtpClose: suspend () -> Unit,
        val session: com.uvp.sim.domain.PlaybackSession? = null,
        val mode: MediaMode = MediaMode.PLAYBACK,
    )

    enum class MediaMode { PLAYBACK, DOWNLOAD }

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
        activePlayback?.let { pb ->
            pb.playbackJob.cancel()
            try { pb.rtpClose() } catch (_: Throwable) {}
            activePlayback = null
        }
        mutex.withLock {
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            awaitingAckCallId = null
        }
    }

    // ---- BroadcastInvoker 实现:T4.2 sendBroadcastInvite 迁移点 ----
    override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) {
        sendBroadcastInvite(sourceId, platformUri, targetId)
    }

    // 真实方法体由后续 Edit 注入,这里先放占位 stub 防编译破
    private suspend fun handleResponse(resp: SipResponse): RoutingResult {
        // 仅吃发出去的 INVITE 响应(主叫 broadcast)
        val cseqHeader = resp.cseqRaw() ?: return RoutingResult.Skip
        val cseqMethod = cseqHeader.split(" ").getOrNull(1)?.let { SipMethod.fromString(it) }
            ?: return RoutingResult.Skip
        return when (cseqMethod) {
            SipMethod.INVITE -> {
                handleBroadcastInviteResponse(resp)
                RoutingResult.Handled
            }
            else -> RoutingResult.Skip
        }
    }

    private suspend fun handleRequest(req: SipRequest): RoutingResult {
        return when (req.method) {
            SipMethod.INVITE -> { handleInvite(req); RoutingResult.Handled }
            SipMethod.ACK -> { handleAck(req); RoutingResult.Handled }
            SipMethod.BYE -> { handleBye(req); RoutingResult.Handled }
            SipMethod.CANCEL -> { handleCancel(req); RoutingResult.Handled }
            SipMethod.INFO -> handleInfoMaybe(req)
            else -> RoutingResult.Skip
        }
    }

    /**
     * INFO 路由:有 activePlayback 才吃,否则 Skip 让 Mans 接(MANSCDP body 路径)。
     * 决策点见 plan §4 风险段。
     */
    private suspend fun handleInfoMaybe(req: SipRequest): RoutingResult {
        if (activePlayback == null) return RoutingResult.Skip
        handleInfo(req)
        return RoutingResult.Handled
    }

    // ----------------------------------------------------------------
    // 通用 helper(从 Engine parseUri/parseTag/parseUriUser 1:1 复制)
    // ----------------------------------------------------------------

    private fun parseUri(headerValue: String): String {
        val lt = headerValue.indexOf('<')
        val gt = headerValue.indexOf('>')
        return if (lt >= 0 && gt > lt) headerValue.substring(lt + 1, gt)
        else headerValue.substringBefore(';').trim()
    }

    private fun parseTag(headerValue: String): String {
        val idx = headerValue.indexOf(";tag=")
        if (idx < 0) return ""
        val rest = headerValue.substring(idx + 5)
        val end = rest.indexOfAny(charArrayOf(';', ' ', '>', '\r', '\n'))
        return if (end < 0) rest else rest.substring(0, end)
    }

    private fun parseUriUser(uri: String): String {
        val s = uri.substringAfter("sip:", uri).substringBefore('@', "")
        return s.ifEmpty { config.server.serverId }
    }

    private fun buildSdpMediaSpec(): com.uvp.sim.sip.SdpAnswer.MediaSpec {
        val v = config.video
        val videoCodec = when (v.videoCodec) {
            com.uvp.sim.media.VideoCodec.H264 -> 2
            com.uvp.sim.media.VideoCodec.H265 -> 5
        }
        val resolution = when (v.resolution) {
            com.uvp.sim.config.VideoResolution.SD_480P -> 4
            com.uvp.sim.config.VideoResolution.HD_720P -> 5
            com.uvp.sim.config.VideoResolution.FHD_1080P -> 6
        }
        val audioCodec = when (v.audioCodec) {
            com.uvp.sim.media.AudioCodec.G711A -> 1
            com.uvp.sim.media.AudioCodec.G711U -> 2
            com.uvp.sim.media.AudioCodec.AAC -> 11
        }
        val audioBitrateKbps = when (v.audioCodec) {
            com.uvp.sim.media.AudioCodec.G711A,
            com.uvp.sim.media.AudioCodec.G711U -> 64
            com.uvp.sim.media.AudioCodec.AAC -> 32
        }
        val audioSampleRate = when (v.effectiveAudioSampleRateHz) {
            8_000 -> 1
            14_000 -> 2
            16_000 -> 3
            32_000 -> 4
            else -> 3
        }
        return com.uvp.sim.sip.SdpAnswer.MediaSpec(
            videoCodec = videoCodec,
            resolution = resolution,
            frameRate = v.frameRate,
            rateType = 2,
            videoBitrateKbps = v.bitrateKbps,
            audioCodec = audioCodec,
            audioBitrateKbps = audioBitrateKbps,
            audioSampleRate = audioSampleRate,
        )
    }

    private fun extractInviteTarget(invite: SipRequest): String {
        val ru = invite.requestUri
        val sipBody = ru.substringAfter("sip:", "").substringAfter("sips:", ru.substringAfter("sip:", ""))
        val userHost = if (sipBody.isNotEmpty()) sipBody else ""
        val user = userHost.substringBefore('@', "").substringBefore(';').trim()
        if (user.isNotEmpty()) return user
        val to = invite.toHeader() ?: return ""
        return parseUri(to).substringAfter("sip:", "")
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
            transport.send(resp)
            simEventEmit(SimEvent.MessageSent(resp))
        }
    }

    private suspend fun sendBusyResponse(req: SipRequest, reason: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req, statusCode = 486, reasonPhrase = "Busy Here",
                toTag = SipBuilders.randomTag(),
                userAgent = config.userAgent,
            )
            transport.send(resp)
            simEventEmit(SimEvent.MessageSent(resp))
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
            transport.send(resp)
            simEventEmit(SimEvent.MessageSent(resp))
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
                transport.send(resp)
                simEventEmit(SimEvent.MessageSent(resp))
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "拒绝 INVITE: channelId=$channelId → ${rejection.first} ${rejection.second}"
                )
            } catch (e: Throwable) {
                simEventEmit(SimEvent.TransportError("send INVITE reject: ${e.message}"))
            }
            return
        }

        // Probe SDP s= 决定 Play vs Playback
        val isPlayback = try {
            val offer = com.uvp.sim.sip.SdpPlaybackParser.parse(invite.body)
            offer.isPlayback
        } catch (_: Throwable) { false }

        if (isPlayback) {
            handlePlaybackInvite(invite)
            return
        }

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
            simEventEmit(SimEvent.TransportError("SDP parse: ${e.message}"))
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
            val cls = e::class.simpleName ?: "?"
            val msg = e.message ?: "<null>"
            val cause = e.cause?.let { "${it::class.simpleName}: ${it.message}" } ?: "<no cause>"
            simEventEmit(SimEvent.TransportError(
                "RTP bind: mode=$rtpMode → ${offer.remoteIp}:${offer.remotePort}  $cls/$msg  cause=$cause"
            ))
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
            mediaSpec = buildSdpMediaSpec(),
        )
        val deviceContact = "<sip:${config.device.deviceId}@$localIp:${localPortProvider()}>"
        val localToTag = SipBuilders.randomTag()
        val inviteFromUser = parseUriUser(parseUri(invite.fromHeader() ?: ""))
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
        val remoteUri = parseUri(inviteFromHeader)
        val remoteTag = parseTag(inviteFromHeader)
        val localUri = parseUri(inviteToHeader)
        val remoteTarget = parseUri(inviteContact).ifEmpty { remoteUri }
        try {
            transport.send(response)
            simEventEmit(SimEvent.MessageSent(response))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send 200 OK: ${e.message}"))
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
                simEventEmit(SimEvent.TransportError("RTP video send: ${e.message}"))
                scope.launch { stopActiveStream(cid, "video send failed: ${e.message}") }
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
                    simEventEmit(SimEvent.TransportError("RTP audio send: ${e.message}"))
                    scope.launch { stopActiveStream(cid, "audio send failed: ${e.message}") }
                }
            }
        }

        // RTCP SR 反馈
        val rtcp = sender(offer.remoteIp, offer.remotePort + 1, RtpMode.UDP)
        try { rtcp.bindLocalPort() } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("RTCP bind: ${e.message}"))
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
            transport.send(ok)
            simEventEmit(SimEvent.MessageSent(ok))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send CANCEL 200: ${e.message}"))
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
            transport.send(ok)
            simEventEmit(SimEvent.MessageSent(ok))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send BYE 200: ${e.message}"))
        }
        // 区分 BYE 走 activeStream 还是 activePlayback
        val playback = activePlayback
        if (playback != null && playback.callId == cid) {
            stopActivePlayback("remote BYE")
        } else {
            stopActiveStream(cid, "remote BYE")
        }
        if (mutableSipState.value == SipState.InCall) {
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.ByeReceived)
        }
        simEventEmit(SimEvent.CallEnded(cid, "remote BYE"))
    }

    private suspend fun handleInfo(req: SipRequest) {
        val pb = activePlayback
        if (pb == null) {
            sendSimpleResponse(req, 481, "Call/Transaction Does Not Exist")
            return
        }
        val cmd = try {
            com.uvp.sim.sip.MansRtspParser.parse(req.body.decodeToString())
        } catch (e: com.uvp.sim.sip.MansRtspParseException) {
            sendSimpleResponse(req, 400, "Bad Request: ${e.message}")
            SystemLogger.emit(LogLevel.Warning, LogTag.Lifecycle, "INFO_PARSE_ERROR: ${e.message}")
            return
        }
        sendSimpleResponse(req, 200, "OK")
        val session = pb.session
        scope.launch {
            when (cmd) {
                is com.uvp.sim.sip.MansRtspCommand.Play -> {
                    cmd.scale?.let { session?.setScale(it) }
                    val rangeMs = cmd.rangeStartMs
                    if (rangeMs != null) session?.seek(rangeMs) else session?.resume()
                }
                is com.uvp.sim.sip.MansRtspCommand.Pause -> session?.pause()
                is com.uvp.sim.sip.MansRtspCommand.Teardown -> stopActivePlayback("INFO TEARDOWN")
            }
        }
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

    private suspend fun stopActivePlayback(reason: String) {
        val pb = activePlayback ?: return
        activePlayback = null
        pb.playbackJob.cancel()
        try { pb.rtpClose() } catch (_: Throwable) {}
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "回放中断 → reason=$reason")
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
            transport.send(bye)
            simEventEmit(SimEvent.MessageSent(bye))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Lifecycle,
                "主动 BYE 终止推流: $reason"
            )
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send BYE: ${e.message}"))
        }
        stopActiveStream(active.callId, reason)
        if (mutableSipState.value == SipState.InCall) {
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.CallEnded)
        }
    }
    private suspend fun handlePlaybackInvite(invite: SipRequest) {
        val cid = invite.callId() ?: ""
        if (activePlayback != null) {
            sendBusyResponse(invite, "已有回放进行中")
            return
        }
        val builder = playbackBuilder
        if (builder == null) {
            sendNotFoundResponse(invite, "PLAYBACK 能力未配置")
            return
        }
        val offer = try {
            com.uvp.sim.sip.SdpPlaybackParser.parse(invite.body)
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("PLAYBACK SDP parse: ${e.message}"))
            sendNotFoundResponse(invite, "SDP 解析失败")
            return
        }
        val segments = recordingService.files.value.filter {
            offer.startMs <= it.endTimeMs && offer.endMs >= it.startTimeMs &&
                (offer.channelId == null || it.channelId == offer.channelId)
        }.sortedBy { it.startTimeMs }
        if (segments.isEmpty()) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "平台 PLAYBACK 区间无录像 → 487 startSec=${offer.startEpochSec} endSec=${offer.endEpochSec}"
            )
            sendNotFoundResponse(invite, "区间无录像")
            return
        }

        val ssrc = offer.ssrc ?: com.uvp.sim.sip.SsrcUtils.generate(
            realtime = false,
            domainCode = config.server.domain.takeLast(5).padStart(5, '0'),
            sequence = (cseq + 1) and 0x0FFF,
        )

        val playback = builder.build(offer, segments, ssrc) ?: run {
            sendNotFoundResponse(invite, "PLAYBACK 构造失败")
            return
        }

        val sessionMode = if (offer.isDownload) MediaMode.DOWNLOAD else MediaMode.PLAYBACK
        val sessionName = if (offer.isDownload) "Download" else "Playback"
        val sdpAnswer = com.uvp.sim.sip.SdpAnswer.buildPlayAnswer(
            deviceId = config.device.deviceId,
            localIp = localIp,
            localRtpPort = playback.localRtpPort,
            ssrc = ssrc,
            sessionName = sessionName,
            mediaSpec = buildSdpMediaSpec(),
        )
        val deviceContact = "<sip:${config.device.deviceId}@$localIp:${localPortProvider()}>"
        val pbInviteFromUser = parseUriUser(parseUri(invite.fromHeader() ?: ""))
        val response = SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = deviceContact,
            toTag = SipBuilders.randomTag(),
            sdpBody = sdpAnswer,
            userAgent = config.userAgent,
            subject = SipBuilders.subject(
                senderId = config.device.deviceId,
                ssrc = ssrc,
                receiverId = pbInviteFromUser,
            ),
        )
        try {
            transport.send(response)
            simEventEmit(SimEvent.MessageSent(response))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send ${sessionName.uppercase()} 200: ${e.message}"))
            playback.cancel()
            return
        }
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "${if (offer.isDownload) "DOWNLOAD_START" else "开始回放"} → ${segments.size} 段 spanMs=${offer.endMs - offer.startMs} ssrc=$ssrc " +
                "localRtpPort=${playback.localRtpPort} target=${offer.remoteIp}:${offer.remotePort}" +
                if (offer.isDownload) " downloadSpeed=${offer.downloadSpeed}" else ""
        )

        if (offer.isDownload && offer.downloadSpeed != 1.0) {
            playback.setScale(offer.downloadSpeed)
        }

        val job = scope.launch {
            try {
                playback.run()
                if (offer.isDownload) {
                    sendMediaStatusNotify()
                    SystemLogger.emit(LogLevel.Info, LogTag.Media, "DOWNLOAD_COMPLETE → MediaStatus Notify 已发")
                }
                sendBye(cid, ssrc, deviceContact, invite)
                runCatching { playback.cancel() }
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "${if (offer.isDownload) "下载完成" else "回放完成"} → 主动 BYE"
                )
            } catch (e: Throwable) {
                simEventEmit(SimEvent.TransportError("${sessionName.uppercase()} error: ${e.message}"))
                SystemLogger.emit(LogLevel.Error, LogTag.Media, "${sessionName} 异常: ${e.message}")
                runCatching { playback.cancel() }
            } finally {
                activePlayback = null
                _state.value = InviteState.Idle
            }
        }
        activePlayback = ActivePlayback(
            callId = cid,
            ssrc = ssrc,
            playbackJob = job,
            rtpClose = playback::cancel,
            session = playback,
            mode = sessionMode,
        )
        _state.value = InviteState.Streaming
    }

    private suspend fun sendBye(callId: String, ssrc: String, deviceContact: String, originalInvite: SipRequest) {
        runCatching {
            val cseqLocal = cseqInc()
            val byeReq = SipRequest(
                method = SipMethod.BYE,
                requestUri = originalInvite.requestUri,
                headers = listOf(
                    SipMessage.Header(SipHeader.VIA,
                        "SIP/2.0/${config.transport.name} $localIp:${localPortProvider()};branch=${SipBuilders.randomBranch()}"),
                    SipMessage.Header(SipHeader.FROM,
                        originalInvite.headers.firstOrNull { SipHeader.canonicalize(it.name) == SipHeader.TO }?.value
                            ?: "<sip:${config.device.deviceId}@${config.server.domain}>"),
                    SipMessage.Header(SipHeader.TO,
                        originalInvite.headers.firstOrNull { SipHeader.canonicalize(it.name) == SipHeader.FROM }?.value
                            ?: "<sip:${config.server.serverId}@${config.server.domain}>"),
                    SipMessage.Header(SipHeader.CALL_ID, callId),
                    SipMessage.Header(SipHeader.CSEQ, "$cseqLocal BYE"),
                    SipMessage.Header(SipHeader.CONTACT, deviceContact),
                    SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
                    SipMessage.Header(SipHeader.USER_AGENT, config.userAgent),
                    SipMessage.Header(SipHeader.DATE, SipBuilders.rfc1123Date()),
                ),
                body = ByteArray(0),
            )
            transport.send(byeReq)
            simEventEmit(SimEvent.MessageSent(byeReq))
        }
    }

    private var notifySn = 0
    private suspend fun sendMediaStatusNotify() {
        runCatching {
            val cseqLocal = cseqInc()
            notifySn += 1
            val req = com.uvp.sim.sip.MediaStatusNotify.build(
                config = config,
                cseq = cseqLocal,
                callId = SipBuilders.randomCallId(localIp),
                branch = SipBuilders.randomBranch(),
                fromTag = SipBuilders.randomTag(),
                localIp = localIp,
                localPort = localPortProvider(),
                sn = notifySn,
            )
            transport.send(req)
            simEventEmit(SimEvent.MessageSent(req))
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "MediaStatus Notify 发送失败: ${it.message}(BYE 仍发)"
            )
        }
    }

    // ----------------------------------------------------------------
    // Broadcast handshake(发反向 INVITE + 处理 200/4xx 响应)
    // RX 媒体链留 Engine,通过 BroadcastDialogHandshakeListener 反向调
    // ----------------------------------------------------------------

    private data class BroadcastInflight(
        val callId: String,
        val fromTag: String,
        val cseq: Int,
        val sourceId: String,
        val targetId: String,
        val platformUri: String,
        val mode: RtpMode,
        val createdAtMs: Long,
    )

    private var broadcastInflight: BroadcastInflight? = null

    private suspend fun sendBroadcastInvite(sourceId: String, platformUri: String, targetId: String) {
        val mode = when (config.audioTransport) {
            com.uvp.sim.config.AudioTransportType.UDP -> RtpMode.UDP
            com.uvp.sim.config.AudioTransportType.TCP_ACTIVE -> RtpMode.TCP_ACTIVE
            com.uvp.sim.config.AudioTransportType.TCP_PASSIVE -> RtpMode.TCP_PASSIVE
        }
        // 让 Engine 同步 bind RTP receiver 拿本地音频端口(PR4 临时桥)
        val boundPort = runCatching {
            broadcastHandshakeListener.bindBroadcastRtpPort(mode)
        }.getOrDefault(-1)
        if (boundPort < 0) {
            simEventEmit(SimEvent.TransportError("broadcast bind failed"))
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "语音广播绑定本地端口失败 → 放弃 INVITE")
            return
        }
        val localAudioPort = boundPort
        val deviceSsrc = com.uvp.sim.sip.SsrcUtils.generate(
            realtime = true,
            domainCode = config.server.domain.takeLast(5).padStart(5, '0'),
            sequence = (cseq + 1) and 0x0FFF,
        )
        val sdpTransport = if (mode == RtpMode.UDP) com.uvp.sim.sip.SdpTransport.UDP else com.uvp.sim.sip.SdpTransport.TCP
        val sdpSetup = if (mode == RtpMode.TCP_ACTIVE) com.uvp.sim.sip.SdpTcpSetup.ACTIVE else com.uvp.sim.sip.SdpTcpSetup.PASSIVE
        val sdp = com.uvp.sim.sip.SdpAnswer.buildBroadcastOffer(
            deviceId = targetId,
            localIp = localIp,
            localAudioPort = localAudioPort,
            deviceSsrc = deviceSsrc,
            transport = sdpTransport,
            tcpSetup = sdpSetup,
        )
        val callIdBc = SipBuilders.randomCallId(localIp)
        val branch = SipBuilders.randomBranch()
        val fromTagBc = SipBuilders.randomTag()
        val inviteCseq = 1
        val invite = SipBuilders.buildOutboundInvite(
            config = config,
            localId = targetId,
            platformUri = platformUri,
            sourceId = sourceId,
            deviceSsrc = deviceSsrc,
            sdpBody = sdp,
            localIp = localIp,
            localPort = localPortProvider(),
            cseq = inviteCseq,
            callId = callIdBc,
            branch = branch,
            fromTag = fromTagBc,
        )
        broadcastInflight = BroadcastInflight(
            callId = callIdBc, fromTag = fromTagBc, cseq = inviteCseq,
            sourceId = sourceId, targetId = targetId, platformUri = platformUri,
            mode = mode, createdAtMs = nowMs(),
        )
        // 通知 Engine BroadcastDialog 进 Inviting 状态
        broadcastHandshakeListener.onInviting(
            callId = callIdBc, fromTag = fromTagBc, cseq = inviteCseq,
            sourceId = sourceId, targetId = targetId, platformUri = platformUri,
            localAudioPort = localAudioPort, deviceSsrc = deviceSsrc, mode = mode,
        )
        runCatching {
            transport.send(invite)
            simEventEmit(SimEvent.MessageSent(invite))
            simEventEmit(SimEvent.BroadcastInvited(platformUri, localAudioPort))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "反向 INVITE 已发: 通道 $targetId → $platformUri, ssrc=$deviceSsrc 本地音频端口=$localAudioPort"
            )
        }.onFailure {
            broadcastInflight = null
            simEventEmit(SimEvent.TransportError("send broadcast INVITE: ${it.message}"))
            broadcastHandshakeListener.onFailed(callIdBc, BroadcastEndReasonHint.Error)
        }
    }

    private suspend fun handleBroadcastInviteResponse(resp: SipResponse) {
        val bc = broadcastInflight ?: return
        if (resp.callId() != bc.callId) return
        when (resp.statusCode) {
            in 100..199 -> return
            in 200..299 -> {
                val remoteTag = parseTag(resp.toHeader() ?: "")
                val contactUri = resp.firstHeader(SipHeader.CONTACT)?.let { parseUri(it) } ?: bc.platformUri
                sendBroadcastAck(
                    callId = bc.callId, fromTag = bc.fromTag, cseqLocal = bc.cseq,
                    sourceId = bc.sourceId, targetId = bc.targetId,
                    platformUri = bc.platformUri, contactUri = contactUri, remoteTag = remoteTag,
                )
                val answer = runCatching {
                    com.uvp.sim.sip.SdpParser.parseAnswer(resp.body.decodeToString())
                }.getOrNull()
                val codec = answer?.payloadTypes?.firstNotNullOfOrNull { com.uvp.sim.domain.AudioRxCodec.fromPayloadType(it) }
                if (answer == null || codec == null) {
                    sendBroadcastBye(
                        callId = bc.callId, fromTag = bc.fromTag, cseqLocal = bc.cseq,
                        sourceId = bc.sourceId, targetId = bc.targetId,
                        platformUri = bc.platformUri, remoteTag = remoteTag,
                    )
                    broadcastInflight = null
                    broadcastHandshakeListener.onFailed(bc.callId, BroadcastEndReasonHint.CodecRejected)
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "语音广播编码不可接受(payloadTypes=${answer?.payloadTypes}) → BYE"
                    )
                    return
                }
                broadcastInflight = null
                broadcastHandshakeListener.onTalking(
                    callId = bc.callId, remoteTag = remoteTag,
                    remoteHost = answer.remoteIp, remotePort = answer.remotePort, codec = codec,
                )
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "语音广播 handshake 完成 codec=${codec.name} mode=${bc.mode} ← ${answer.remoteIp}:${answer.remotePort}"
                )
            }
            in 400..699 -> {
                broadcastInflight = null
                broadcastHandshakeListener.onFailed(bc.callId, BroadcastEndReasonHint.InviteFailed)
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "语音广播 INVITE 被拒: ${resp.statusCode} ${resp.reasonPhrase}"
                )
            }
        }
    }

    private suspend fun sendBroadcastAck(
        callId: String, fromTag: String, cseqLocal: Int,
        sourceId: String, targetId: String, platformUri: String,
        contactUri: String, remoteTag: String,
    ) {
        runCatching {
            val ack = SipBuilders.buildOutboundAck(
                config = config,
                requestUri = contactUri,
                callId = callId,
                cseq = cseqLocal,
                branch = SipBuilders.randomBranch(),
                deviceUri = "sip:$targetId@${config.server.domain}",
                fromTag = fromTag,
                platformUri = platformUri,
                remoteTag = remoteTag,
                localIp = localIp,
                localPort = localPortProvider(),
            )
            transport.send(ack)
            simEventEmit(SimEvent.MessageSent(ack))
        }.onFailure {
            simEventEmit(SimEvent.TransportError("send broadcast ACK: ${it.message}"))
        }
    }

    private suspend fun sendBroadcastBye(
        callId: String, fromTag: String, cseqLocal: Int,
        sourceId: String, targetId: String, platformUri: String, remoteTag: String,
    ) {
        runCatching {
            val bye = SipBuilders.buildBye(
                config = config,
                callId = callId,
                cseq = cseqLocal + 1,
                branch = SipBuilders.randomBranch(),
                localUri = "sip:$targetId@${config.server.domain}",
                localTag = fromTag,
                remoteUri = platformUri,
                remoteTag = remoteTag,
                remoteTarget = platformUri,
                localIp = localIp,
                localPort = localPortProvider(),
            )
            transport.send(bye)
            simEventEmit(SimEvent.MessageSent(bye))
        }.onFailure {
            simEventEmit(SimEvent.TransportError("send broadcast BYE: ${it.message}"))
        }
    }
}

/** 单测 / Engine 装配前的默认占位:三个回调都 no-op,允许 Coord 在没接 Engine 的环境里跑。 */
internal object NoopBroadcastDialogHandshakeListener : BroadcastDialogHandshakeListener {
    override suspend fun bindBroadcastRtpPort(mode: RtpMode): Int = 50000
    override suspend fun onInviting(
        callId: String, fromTag: String, cseq: Int, sourceId: String, targetId: String,
        platformUri: String, localAudioPort: Int, deviceSsrc: String, mode: RtpMode,
    ) {}
    override suspend fun onTalking(
        callId: String, remoteTag: String, remoteHost: String, remotePort: Int,
        codec: com.uvp.sim.domain.AudioRxCodec,
    ) {}
    override suspend fun onFailed(callId: String, reason: BroadcastEndReasonHint) {}
}

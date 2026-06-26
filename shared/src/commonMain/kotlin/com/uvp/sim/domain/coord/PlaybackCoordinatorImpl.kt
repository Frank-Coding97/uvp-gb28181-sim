package com.uvp.sim.domain.coord

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipHeaderHelpers
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [PlaybackCoordinator] 真实现(PR5 T5.2 GREEN)。
 *
 * 接管 InviteCoordinatorImpl 上的回放路径(handlePlaybackInvite / handleInfo MANSRTSP /
 * sendBye / sendMediaStatusNotify / stopActivePlayback / ActivePlayback / MediaMode)。
 */
internal class PlaybackCoordinatorImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val outbox: com.uvp.sim.sip.SipOutbox,
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val playbackBuilder: com.uvp.sim.domain.PlaybackBuilder? = null,
    private val recordingService: RecordingService = com.uvp.sim.recording.NoopRecordingService,
    private val simEventEmit: suspend (SimEvent) -> Unit = {},
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
) : PlaybackCoordinator {

    private val _state = MutableStateFlow(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

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

    private var activePlayback: ActivePlayback? = null
    private var notifySn = 0

    private data class ActivePlayback(
        val callId: String,
        val ssrc: String,
        val playbackJob: Job,
        val rtpClose: suspend () -> Unit,
        val session: com.uvp.sim.domain.PlaybackSession? = null,
        val mode: PlaybackMediaMode = PlaybackMediaMode.PLAYBACK,
    )

    override suspend fun onIncoming(msg: SipMessage): RoutingResult = when (msg) {
        is SipRequest -> handleRequest(msg)
        is SipResponse -> RoutingResult.Skip
    }

    private suspend fun handleRequest(req: SipRequest): RoutingResult {
        return when (req.method) {
            SipMethod.INVITE -> {
                val isPlayback = try {
                    com.uvp.sim.sip.SdpPlaybackParser.parse(req.body).isPlayback
                } catch (_: Throwable) { false }
                if (isPlayback) { handlePlaybackInvite(req); RoutingResult.Handled }
                else RoutingResult.Skip
            }
            SipMethod.INFO -> {
                if (activePlayback == null) RoutingResult.Skip
                else { handleInfo(req); RoutingResult.Handled }
            }
            SipMethod.BYE -> {
                val cid = req.callId() ?: ""
                val pb = activePlayback
                if (pb != null && pb.callId == cid) {
                    runCatching {
                        val ok = SipBuilders.buildSimple200(req, userAgent = config.userAgent)
                        outbox.send(ok).getOrThrow()
                    }
                    stopActivePlayback("remote BYE")
                    simEventEmit(SimEvent.CallEnded(cid, "remote BYE"))
                    RoutingResult.Handled
                } else RoutingResult.Skip
            }
            else -> RoutingResult.Skip
        }
    }

    override suspend fun shutdown() {
        activePlayback?.let {
            it.playbackJob.cancel()
            try { it.rtpClose() } catch (_: Throwable) {}
            activePlayback = null
        }
        _state.value = PlaybackState.Idle
    }

    override suspend fun stop(reason: String) {
        stopActivePlayback(reason)
    }

    private suspend fun sendSimpleResponse(req: SipRequest, statusCode: Int, reasonPhrase: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req, statusCode = statusCode, reasonPhrase = reasonPhrase,
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
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "拒绝 PLAYBACK INVITE → 486 ($reason)")
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
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "拒绝 PLAYBACK INVITE → 487 ($reason)")
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
            simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("PLAYBACK SDP parse", e)))
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

        val sessionMode = if (offer.isDownload) PlaybackMediaMode.DOWNLOAD else PlaybackMediaMode.PLAYBACK
        val sessionName = if (offer.isDownload) "Download" else "Playback"
        val sdpAnswer = com.uvp.sim.sip.SdpAnswer.buildPlayAnswer(
            deviceId = config.device.deviceId,
            localIp = localIp,
            localRtpPort = playback.localRtpPort,
            ssrc = ssrc,
            sessionName = sessionName,
            mediaSpec = SipHeaderHelpers.buildSdpMediaSpec(config),
        )
        val deviceContact = "<sip:${config.device.deviceId}@$localIp:${localPortProvider()}>"
        val pbInviteFromUser = SipHeaderHelpers.parseUriUser(
            SipHeaderHelpers.parseUri(invite.fromHeader() ?: ""),
            fallback = config.server.serverId,
        )
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
            outbox.send(response).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("send ${sessionName.uppercase()} 200", e)))
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
                simEventEmit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("${sessionName.uppercase()} error", e)))
                SystemLogger.emit(LogLevel.Error, LogTag.Media, "${sessionName} 异常: ${e.message}")
                runCatching { playback.cancel() }
            } finally {
                activePlayback = null
                _state.value = PlaybackState.Idle
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
        _state.value = PlaybackState.Playing
    }

    private suspend fun handleInfo(req: SipRequest) {
        val pb = activePlayback ?: return
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

    private suspend fun stopActivePlayback(reason: String) {
        val pb = activePlayback ?: return
        activePlayback = null
        pb.playbackJob.cancel()
        try { pb.rtpClose() } catch (_: Throwable) {}
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "回放中断 → reason=$reason")
        _state.value = PlaybackState.Idle
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
            outbox.send(byeReq).getOrThrow()
        }
    }

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
            outbox.send(req).getOrThrow()
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "MediaStatus Notify 发送失败: ${it.message}(BYE 仍发)"
            )
        }
    }
}

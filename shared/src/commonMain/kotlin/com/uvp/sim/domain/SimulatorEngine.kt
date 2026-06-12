package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.Heartbeat
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.DigestAuth
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

/**
 * Top-level orchestrator that wires SIP state machine, transport, auth, and
 * heartbeat together. UI subscribes to [state] and [events]; user actions go
 * through [register], [unregister].
 *
 * The engine is stateful and intentionally single-instance per session.
 *
 * Design notes:
 *   - The transport layer is injected so tests can supply a mock.
 *   - All side effects happen inside the engine; the SIP state machine itself
 *     remains a pure function.
 *   - localIp / localPort are configurable for tests; production code should
 *     resolve them from the bound transport.
 */
class SimulatorEngine(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val scope: CoroutineScope,
    private val localIp: String = "0.0.0.0",
    private val localPortProvider: () -> Int = { 5060 },
    private val cameraCapture: com.uvp.sim.camera.CameraCapture? = null,
    private val audioCapture: com.uvp.sim.camera.AudioCapture? = null,
    private val rtpSenderFactory: ((host: String, port: Int) -> com.uvp.sim.network.RtpSender)? = null
) {
    private val _state = MutableStateFlow(SipState.Disconnected)
    val state: StateFlow<SipState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SimEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SimEvent> = _events.asSharedFlow()

    private val mutex = Mutex()
    private var registerJob: Job? = null
    private var inboundJob: Job? = null
    private var heartbeat: Heartbeat? = null

    // Per-registration session state
    private var cseq: Int = 0
    private var callId: String? = null
    private var fromTag: String? = null
    private var pendingRegister: SipRequest? = null
    private var keepaliveSn: Int = 0

    // Active streaming session state (M1: at most one)
    private var activeStream: ActiveStream? = null

    private data class ActiveStream(
        val callId: String,
        val ssrc: String,
        val rtpSender: com.uvp.sim.network.RtpSender,
        val streamJob: Job,
        val audioJob: Job? = null,
        val statsJob: Job? = null,
        var frameCount: Int = 0,
        var packetCount: Int = 0
    )

    /** Initiate registration. Returns immediately; observe [state] for completion. */
    suspend fun register() {
        mutex.withLock {
            if (_state.value == SipState.Registering ||
                _state.value == SipState.Registered) return
            startInboundIfNeeded()

            cseq = 1
            callId = SipBuilders.randomCallId(localIp)
            fromTag = SipBuilders.randomTag()
            val branch = SipBuilders.randomBranch()
            val req = SipBuilders.buildRegister(
                config, cseq, callId!!, branch, fromTag!!, localIp, localPortProvider()
            )
            pendingRegister = req
            _state.value = SipStateMachine.transition(_state.value, SipEvent.RegisterRequested)
            _events.emit(SimEvent.RegistrationStarted("${config.server.ip}:${config.server.port}"))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Lifecycle,
                "开始注册到 ${config.server.ip}:${config.server.port}"
            )
            try {
                transport.send(req)
                _events.emit(SimEvent.MessageSent(req))
                armRegisterTimeout()
            } catch (e: Throwable) {
                _state.value = SipStateMachine.transition(
                    _state.value, SipEvent.RegisterFailed("transport.send: ${e.message}")
                )
                _events.emit(SimEvent.TransportError("send REGISTER: ${e::class.simpleName}: ${e.message}"))
                _events.emit(SimEvent.RegistrationFailed("transport: ${e.message}"))
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Lifecycle,
                    "注册请求发送失败: ${e::class.simpleName}: ${e.message}"
                )
            }
        }
    }

    /** Cancel an in-flight REGISTER (before any response). Used by UI cancel. */
    suspend fun cancelRegister() {
        mutex.withLock {
            if (_state.value != SipState.Registering) return
            registerJob?.cancel()
            registerJob = null
            _state.value = SipStateMachine.transition(
                _state.value, SipEvent.UnregisterRequested
            )
            _events.emit(SimEvent.RegistrationFailed("用户取消"))
        }
    }

    /**
     * Arm a watchdog so the UI doesn't sit in "Registering…" forever when the
     * platform never replies (e.g. server down, UDP black hole, wrong IP).
     *
     * Re-armed on each REGISTER send (initial + 401 re-send), cancelled when
     * we either succeed, get a terminal failure, or the user cancels.
     */
    private fun armRegisterTimeout() {
        registerJob?.cancel()
        registerJob = scope.launch {
            delay(REGISTER_TIMEOUT_MS)
            mutex.withLock {
                if (_state.value != SipState.Registering) return@withLock
                _state.value = SipStateMachine.transition(
                    _state.value,
                    SipEvent.RegisterFailed("timeout")
                )
                _events.emit(SimEvent.RegistrationFailed("平台 ${REGISTER_TIMEOUT_MS / 1000}s 未响应"))
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Lifecycle,
                    "注册超时: 平台 ${REGISTER_TIMEOUT_MS / 1000}s 未响应"
                )
            }
        }
    }

    private fun cancelRegisterTimeout() {
        registerJob?.cancel()
        registerJob = null
    }

    /** Send Unregister and tear down. */
    suspend fun unregister() {
        mutex.withLock {
            if (_state.value == SipState.Disconnected) return
            cancelRegisterTimeout()
            heartbeat?.stop()
            heartbeat = null
            SystemLogger.emit(
                LogLevel.Info, LogTag.Lifecycle,
                "用户注销 → 发送 Unregister"
            )

            cseq += 1
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: SipBuilders.randomTag()
            val req = SipBuilders.buildUnregister(
                config, cseq, callIdNow, branch, fromTagNow, localIp, localPortProvider()
            )
            transport.send(req)
            _events.emit(SimEvent.MessageSent(req))
            _state.value = SipStateMachine.transition(_state.value, SipEvent.UnregisterRequested)
        }
    }

    /** Stop background work. The transport itself is not closed (caller's responsibility). */
    suspend fun shutdown() {
        // 先把活跃流停掉(取消 statsJob / 关 RTP socket / 释放 camera),
        // 不能持锁内调用 stopActiveStream(它会再 take 锁)。
        activeStream?.let { active ->
            active.statsJob?.cancel()
            active.streamJob.cancel()
            active.audioJob?.cancel()
            try { active.rtpSender.close() } catch (_: Throwable) { }
            activeStream = null
        }
        mutex.withLock {
            heartbeat?.stop()
            heartbeat = null
            inboundJob?.cancel()
            inboundJob = null
            registerJob?.cancel()
            registerJob = null
            _state.value = SipState.Disconnected
        }
    }

    /**
     * T15 — Snapshot upload (主动业务: 抓拍上报).
     *
     * GB28181 § 9.5 device-side snapshot is implemented as an Alarm Notify with
     * a Catalog-like Item naming the snapshot. M1 sends just the SIP MESSAGE
     * Notify; the actual JPEG upload (HTTP PUT to a media gateway URL the
     * platform sends back in the snapshot command) is M2.
     *
     * In the typical "device-initiated" snapshot flow used here, we send the
     * Notify and let the platform follow up with a HTTP request to fetch the
     * image; if the platform doesn't ask within a few seconds, we just ignore.
     */
    suspend fun reportSnapshot() {
        mutex.withLock {
            if (_state.value != SipState.Registered && _state.value != SipState.InCall) {
                _events.emit(SimEvent.TransportError("snapshot: not registered"))
                return
            }
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val callIdNow = callId ?: com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: com.uvp.sim.sip.SipBuilders.randomTag()
            val xml = com.uvp.sim.gb28181.AlarmNotify.buildSnapshotAlarm(
                config = config,
                sn = cseq.toString()
            )
            val msg = com.uvp.sim.sip.SipBuilders.buildMessage(
                config = config,
                cseq = cseq,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xml
            )
            try {
                transport.send(msg)
                _events.emit(SimEvent.MessageSent(msg))
                _events.emit(SimEvent.SnapshotReported(cseq.toString()))
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("snapshot send: ${e.message}"))
            }
        }
    }

    private fun startInboundIfNeeded() {
        if (inboundJob != null) return
        inboundJob = scope.launch {
            try {
                transport.incoming.collect { msg ->
                    _events.emit(SimEvent.MessageReceived(msg))
                    try {
                        handleIncoming(msg)
                    } catch (e: Throwable) {
                        _events.emit(SimEvent.TransportError("handleIncoming: ${e::class.simpleName}: ${e.message}"))
                    }
                }
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("inbound: ${e::class.simpleName}: ${e.message}"))
            }
        }
    }

    private suspend fun handleIncoming(msg: SipMessage) {
        when (msg) {
            is SipResponse -> handleResponse(msg)
            is SipRequest -> handleRequest(msg)
        }
    }

    private suspend fun handleResponse(resp: SipResponse) {
        val cseqHeader = resp.cseqRaw() ?: return
        val cseqMethod = cseqHeader.split(" ").getOrNull(1)?.let { SipMethod.fromString(it) } ?: return

        when (cseqMethod) {
            SipMethod.REGISTER -> handleRegisterResponse(resp)
            SipMethod.MESSAGE -> {
                if (resp.statusCode in 200..299) {
                    val sn = keepaliveSn
                    _events.emit(SimEvent.HeartbeatAcknowledged(sn))
                }
            }
            else -> Unit
        }
    }

    private suspend fun handleRegisterResponse(resp: SipResponse) {
        when (resp.statusCode) {
            in 200..299 -> {
                cancelRegisterTimeout()
                _state.value = SipStateMachine.transition(_state.value, SipEvent.Register200Received)
                _events.emit(SimEvent.RegistrationSucceeded(config.expiresSeconds))
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "已注册,expires=${config.expiresSeconds}s"
                )
                startHeartbeat()
            }

            401, 407 -> {
                val challenge = resp.firstHeader(SipHeader.WWW_AUTHENTICATE)
                    ?: resp.firstHeader("Proxy-Authenticate")
                val pending = pendingRegister
                if (challenge == null || pending == null) {
                    cancelRegisterTimeout()
                    _state.value = SipStateMachine.transition(
                        _state.value, SipEvent.RegisterFailed("401 missing WWW-Authenticate")
                    )
                    _events.emit(SimEvent.RegistrationFailed("Missing challenge"))
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Lifecycle,
                        "注册失败: 401 缺少 WWW-Authenticate"
                    )
                    return
                }
                _events.emit(SimEvent.RegistrationChallenged(challenge))
                _state.value = SipStateMachine.transition(
                    _state.value, SipEvent.Register401Received(challenge)
                )

                val parsed = DigestAuth.parseChallenge(challenge)
                val authHeader = DigestAuth.buildResponse(
                    challenge = parsed,
                    username = config.device.username,
                    password = config.device.password,
                    method = "REGISTER",
                    uri = pending.requestUri
                )
                cseq += 1
                val newBranch = SipBuilders.randomBranch()
                val authedReq = SipBuilders.addAuthorization(pending, authHeader, cseq, newBranch)
                pendingRegister = authedReq
                transport.send(authedReq)
                _events.emit(SimEvent.MessageSent(authedReq))
                armRegisterTimeout()
            }

            in 400..699 -> {
                cancelRegisterTimeout()
                val reason = "${resp.statusCode} ${resp.reasonPhrase}"
                _state.value = SipStateMachine.transition(_state.value, SipEvent.RegisterFailed(reason))
                _events.emit(SimEvent.RegistrationFailed(reason))
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Lifecycle,
                    "注册被拒: $reason"
                )
            }
        }
    }

    private suspend fun handleRequest(req: SipRequest) {
        when (req.method) {
            SipMethod.INVITE -> handleInvite(req)
            SipMethod.BYE -> handleBye(req)
            SipMethod.MESSAGE -> handleMessage(req)
            else -> Unit
        }
    }

    private suspend fun handleMessage(message: SipRequest) {
        // Always 200-OK first so the SIP transaction completes promptly.
        try {
            val ok = com.uvp.sim.sip.SipBuilders.buildSimple200(
                message, toTag = com.uvp.sim.sip.SipBuilders.randomTag()
            )
            transport.send(ok)
            _events.emit(SimEvent.MessageSent(ok))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send MESSAGE 200: ${e.message}"))
        }

        // Inspect body — only Catalog query needs a follow-up Notify response.
        val xml = message.body.decodeToString()
        val cmd = com.uvp.sim.gb28181.ManscdpParser.cmdType(xml)
        if (cmd == "Catalog") {
            val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
            sendCatalogResponse(sn)
        }
        // Other CmdTypes (DeviceInfo, DeviceStatus, ...) deferred to M2.
    }

    private suspend fun sendCatalogResponse(sn: String) {
        try {
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val callIdNow = callId ?: com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: com.uvp.sim.sip.SipBuilders.randomTag()
            val xmlBody = com.uvp.sim.gb28181.CatalogResponse.build(config, sn)
            val msg = com.uvp.sim.sip.SipBuilders.buildMessage(
                config = config,
                cseq = cseq,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody
            )
            transport.send(msg)
            _events.emit(SimEvent.MessageSent(msg))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send Catalog response: ${e.message}"))
        }
    }

    private suspend fun handleInvite(invite: SipRequest) {
        _state.value = SipStateMachine.transition(_state.value, SipEvent.InviteReceived)
        val cid = invite.callId() ?: ""
        _events.emit(SimEvent.IncomingInvite(cid))

        val sender = rtpSenderFactory
        val cam = cameraCapture
        if (sender == null || cam == null) {
            // M1 unit-test path: engine constructed without media plumbing —
            // we simply transitioned to InCall and emitted the event so tests
            // can observe; nothing more to do.
            return
        }

        val offer = try {
            com.uvp.sim.sip.SdpParser.parseOffer(invite.body)
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("SDP parse: ${e.message}"))
            return
        }

        val ssrc = offer.ssrc ?: com.uvp.sim.sip.SsrcUtils.generate(
            realtime = true,
            domainCode = config.server.domain.takeLast(5).padStart(5, '0'),
            sequence = (cseq + 1) and 0x0FFF
        )

        val rtp = sender(offer.remoteIp, offer.remotePort)
        val localRtpPort = try {
            rtp.bindLocalPort()
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("RTP bind: ${e.message}"))
            return
        }

        val sdpAnswer = com.uvp.sim.sip.SdpAnswer.buildPlayAnswer(
            deviceId = config.device.deviceId,
            localIp = localIp,
            localRtpPort = localRtpPort,
            ssrc = ssrc,
            sessionName = "Play"
        )
        val deviceContact = "<sip:${config.device.deviceId}@$localIp:${localPortProvider()}>"
        val response = com.uvp.sim.sip.SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = deviceContact,
            toTag = com.uvp.sim.sip.SipBuilders.randomTag(),
            sdpBody = sdpAnswer
        )
        try {
            transport.send(response)
            _events.emit(SimEvent.MessageSent(response))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send 200 OK: ${e.message}"))
            try { rtp.close() } catch (_: Throwable) { }
            return
        }

        _events.emit(SimEvent.StreamStarted(cid, offer.remoteIp, offer.remotePort, ssrc))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "开始推流 → ${offer.remoteIp}:${offer.remotePort} ssrc=$ssrc"
        )

        val packer = com.uvp.sim.media.RtpPacker(
            payloadType = 96,
            ssrc = com.uvp.sim.sip.SsrcUtils.toRtpInt(ssrc)
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
                            try {
                                rtp.send(p)
                                activeStream?.let { it.packetCount += 1 }
                            } catch (e: Throwable) {
                                _events.emit(SimEvent.TransportError("RTP send: ${e.message}"))
                                return@withLock
                            }
                        }
                    }
                    activeStream?.let { it.frameCount += 1 }
                }
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("camera flow: ${e.message}"))
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
                                try {
                                    rtp.send(p)
                                    activeStream?.let { it.packetCount += 1 }
                                } catch (e: Throwable) {
                                    _events.emit(SimEvent.TransportError("RTP audio send: ${e.message}"))
                                    return@withLock
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    _events.emit(SimEvent.TransportError("audio flow: ${e.message}"))
                }
            }
        }

        activeStream = ActiveStream(
            callId = cid,
            ssrc = ssrc,
            rtpSender = rtp,
            streamJob = streamJob,
            audioJob = audioJob,
            statsJob = scope.launch {
                while (true) {
                    delay(MEDIA_STATS_INTERVAL_MS)
                    val a = activeStream ?: break
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "RTP 推送中: ${a.frameCount} 帧 / ${a.packetCount} 包"
                    )
                }
            }
        )
    }

    private suspend fun handleBye(bye: SipRequest) {
        // Send 200 OK back so platform marks call terminated cleanly.
        try {
            val ack = com.uvp.sim.sip.SipBuilders.buildSimple200(bye)
            transport.send(ack)
            _events.emit(SimEvent.MessageSent(ack))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send BYE 200: ${e.message}"))
        }

        val cid = bye.callId() ?: ""
        stopActiveStream(cid, "remote BYE")
        _state.value = SipStateMachine.transition(_state.value, SipEvent.ByeReceived)
        _events.emit(SimEvent.CallEnded(cid, "remote BYE"))
    }

    private suspend fun stopActiveStream(callId: String, reason: String) {
        val active = activeStream ?: return
        activeStream = null
        active.streamJob.cancel()
        active.audioJob?.cancel()
        active.statsJob?.cancel()
        try { active.rtpSender.close() } catch (_: Throwable) { }
        try { cameraCapture?.stop() } catch (_: Throwable) { }
        try { audioCapture?.stop() } catch (_: Throwable) { }
        _events.emit(
            SimEvent.StreamStopped(
                callId = callId,
                frameCount = active.frameCount,
                packetCount = active.packetCount,
                reason = reason
            )
        )
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "停止推流 ($reason): ${active.frameCount} 帧 / ${active.packetCount} 包"
        )
    }

    private fun startHeartbeat() {
        heartbeat?.stop()
        heartbeat = Heartbeat(
            intervalMillis = config.keepaliveIntervalSeconds * 1000L,
            scope = scope
        ) {
            keepaliveSn += 1
            cseq += 1
            val branch = SipBuilders.randomBranch()
            val msg = SipBuilders.buildKeepalive(
                config = config,
                sn = keepaliveSn,
                cseq = cseq,
                callId = callId ?: SipBuilders.randomCallId(localIp),
                branch = branch,
                fromTag = fromTag ?: SipBuilders.randomTag(),
                localIp = localIp,
                localPort = localPortProvider()
            )
            try {
                transport.send(msg)
                _events.emit(SimEvent.HeartbeatSent(keepaliveSn))
                _events.emit(SimEvent.MessageSent(msg))
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("send Keepalive: ${e::class.simpleName}: ${e.message}"))
            }
        }
        heartbeat?.start()
    }

    companion object {
        /**
         * Watchdog so the UI never sits in "Registering…" forever when the
         * platform is dead / wrong IP / firewall black hole. SIP RFC 3261
         * Timer F is 32s but for an interactive simulator UX 8s is plenty —
         * a healthy platform answers in < 1s, and 401-then-200 is < 3s.
         */
        const val REGISTER_TIMEOUT_MS: Long = 8_000L

        /** RTP 周期统计 emit 间隔(spec §6.2 MEDIA emit 时机点)。 */
        const val MEDIA_STATS_INTERVAL_MS: Long = 30_000L
    }
}

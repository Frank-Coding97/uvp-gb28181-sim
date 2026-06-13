package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.MobilePositionNotify
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
import com.uvp.sim.sip.SubscribeHandler
import com.uvp.sim.sip.SubscribeIntent
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
    private val rtpSenderFactory: ((host: String, port: Int, mode: com.uvp.sim.network.RtpMode) -> com.uvp.sim.network.RtpSender)? = null,
    /**
     * 录像引擎(M2 加)。默认 NoopRecordingService,SIP 主路径不会被影响。
     * Android 下由 ViewModel 注入 [com.uvp.sim.recording.AndroidRecordingService]。
     */
    private val recordingService: com.uvp.sim.recording.RecordingService =
        com.uvp.sim.recording.NoopRecordingService,
    /**
     * PLAYBACK 推流构造器(M2 D 块)。null 时引擎收 PLAYBACK INVITE 直接 487。
     * 真实场景由 ViewModel 注入: 拼装 Mp4DemuxFactory + RtpSink + clock。
     */
    private val playbackBuilder: PlaybackBuilder? = null
) {
    private val _state = MutableStateFlow(SipState.Disconnected)
    val state: StateFlow<SipState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SimEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SimEvent> = _events.asSharedFlow()

    /**
     * 5.13 / M2 §F.3 — 设备控制运行时状态.
     * UI 3D 渲染层订阅,Dispatcher 写入.
     */
    private val _deviceControlState = MutableStateFlow(DeviceControlState())
    val deviceControlState: StateFlow<DeviceControlState> = _deviceControlState.asStateFlow()

    private val deviceControlDispatcher: DeviceControlDispatcher by lazy {
        DeviceControlDispatcher(
            state = _deviceControlState,
            config = config,
            actions = object : DeviceControlActions {
                override suspend fun reboot() {
                    SystemLogger.emit(LogLevel.Info, LogTag.Lifecycle, "TeleBoot → 重新注册")
                    try {
                        unregister()
                    } catch (_: Throwable) { /* 平台可能已不可达 */ }
                    delay(1_000L)
                    register()
                }
                override suspend fun snapshot() {
                    reportSnapshot()
                }
                override fun requestKeyFrame() {
                    cameraCapture?.requestKeyFrame()
                }
            },
            scope = scope
        )
    }

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

    // 1.5: Heartbeat timeout detection
    private var consecutiveKeepaliveTimeouts: Int = 0
    private var lastKeepaliveAcked: Boolean = true

    // 1.6: Expires auto-renewal
    private var renewalJob: Job? = null
    private var isRenewal: Boolean = false

    // 1.7: Registration retry with backoff
    private var registerRetryCount: Int = 0
    private var retryJob: Job? = null

    // 5.14: ACK verification after we 200-OK an INVITE
    private var ackTimeoutJob: Job? = null
    private var awaitingAckCallId: String? = null

    // Active streaming session state (M1: at most one)
    private var activeStream: ActiveStream? = null
    /** Active PLAYBACK session(M2 D 块)。与 activeStream 互斥。 */
    private var activePlayback: ActivePlayback? = null

    // M2: Subscription registry + mock GPS
    private val subscriptionRegistry = SubscriptionRegistry(scope)
    private val mockGps = MockGpsSource(config.mockPosition)
    private var notifySn = 0

    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> = subscriptionRegistry.subscriptions

    private data class ActiveStream(
        val callId: String,
        val ssrc: String,
        val rtpSender: com.uvp.sim.network.RtpSender,
        val streamJob: Job,
        val audioJob: Job? = null,
        val statsJob: Job? = null,
        var frameCount: Int = 0,
        var packetCount: Int = 0,
        // Dialog state for device-initiated BYE
        val localUri: String = "",
        val localTag: String = "",
        val remoteUri: String = "",
        val remoteTag: String = "",
        val remoteTarget: String = ""
    )

    private data class ActivePlayback(
        val callId: String,
        val ssrc: String,
        val playbackJob: Job,
        val rtpClose: suspend () -> Unit
    )

    /** Initiate registration. Returns immediately; observe [state] for completion. */
    suspend fun register() {
        mutex.withLock {
            if (_state.value == SipState.Registering ||
                _state.value == SipState.Registered) return
            retryJob?.cancel()
            retryJob = null
            registerRetryCount = 0
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
            retryJob?.cancel()
            retryJob = null
            registerRetryCount = 0
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
                scheduleRetryOrFail("平台 ${REGISTER_TIMEOUT_MS / 1000}s 未响应")
            }
        }
    }

    private fun cancelRegisterTimeout() {
        registerJob?.cancel()
        registerJob = null
    }

    /**
     * 1.7: Decide whether to retry registration or give up.
     * Retries only for transient failures (timeout / 5xx). Permanent rejections
     * (403, 404, bad credentials) go straight to Failed.
     */
    private suspend fun scheduleRetryOrFail(reason: String, permanent: Boolean = false) {
        if (permanent || registerRetryCount >= MAX_REGISTER_RETRIES) {
            _state.value = SipStateMachine.transition(_state.value, SipEvent.RegisterFailed(reason))
            _events.emit(SimEvent.RegistrationFailed(reason))
            SystemLogger.emit(LogLevel.Warning, LogTag.Lifecycle, "注册失败(不重试): $reason")
            registerRetryCount = 0
            return
        }
        registerRetryCount++
        val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl (registerRetryCount - 1))
        _events.emit(SimEvent.RegistrationRetryScheduled(delayMs, registerRetryCount))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Lifecycle,
            "注册失败: $reason → 第 $registerRetryCount 次重试,${delayMs}ms 后"
        )
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            doRegisterInternal()
        }
    }

    /**
     * Internal register logic reused by initial register and retry.
     * Unlike public [register], does NOT reset retryCount.
     */
    private suspend fun doRegisterInternal() {
        mutex.withLock {
            if (_state.value != SipState.Registering &&
                _state.value != SipState.Failed &&
                _state.value != SipState.Disconnected
            ) return
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
            try {
                transport.send(req)
                _events.emit(SimEvent.MessageSent(req))
                armRegisterTimeout()
            } catch (e: Throwable) {
                scheduleRetryOrFail("transport.send: ${e.message}")
            }
        }
    }

    /** Send Unregister and tear down. */
    suspend fun unregister() {
        // 5.5: device-initiated BYE before Unregister if a stream is active.
        // Outside the mutex because stopStream() takes the mutex itself.
        if (activeStream != null) {
            stopStream("user unregister")
        }
        mutex.withLock {
            if (_state.value == SipState.Disconnected) return
            cancelRegisterTimeout()
            retryJob?.cancel()
            retryJob = null
            registerRetryCount = 0
            renewalJob?.cancel()
            renewalJob = null
            heartbeat?.stop()
            heartbeat = null
            subscriptionRegistry.cancelAll()
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
            renewalJob?.cancel()
            renewalJob = null
            retryJob?.cancel()
            retryJob = null
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            awaitingAckCallId = null
            subscriptionRegistry.cancelAll()
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

    /**
     * 5.5: Device-initiated BYE for an active stream.
     * Sends BYE to the platform, awaits no response (200 OK arrives async via
     * handleResponse), then tears down the local pipeline.
     */
    suspend fun stopStream(reason: String = "user stop") {
        val active = activeStream ?: return
        if (active.remoteUri.isEmpty() || active.remoteTag.isEmpty()) {
            // Dialog state incomplete (e.g. test path) — just tear down locally
            stopActiveStream(active.callId, reason)
            return
        }
        try {
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val bye = com.uvp.sim.sip.SipBuilders.buildBye(
                config = config,
                callId = active.callId,
                cseq = cseq,
                branch = branch,
                localUri = active.localUri,
                localTag = active.localTag,
                remoteUri = active.remoteUri,
                remoteTag = active.remoteTag,
                remoteTarget = active.remoteTarget,
                localIp = localIp,
                localPort = localPortProvider()
            )
            transport.send(bye)
            _events.emit(SimEvent.MessageSent(bye))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Lifecycle,
                "主动 BYE 终止推流: $reason"
            )
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send BYE: ${e.message}"))
        }
        stopActiveStream(active.callId, reason)
        if (_state.value == SipState.InCall) {
            _state.value = SipStateMachine.transition(_state.value, SipEvent.CallEnded)
        }
    }

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
                    consecutiveKeepaliveTimeouts = 0
                    lastKeepaliveAcked = true
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
                registerRetryCount = 0
                if (!isRenewal) {
                    _state.value = SipStateMachine.transition(_state.value, SipEvent.Register200Received)
                    _events.emit(SimEvent.RegistrationSucceeded(config.expiresSeconds))
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Lifecycle,
                        "已注册,expires=${config.expiresSeconds}s"
                    )
                    startHeartbeat()
                } else {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Lifecycle,
                        "续约成功,expires=${config.expiresSeconds}s"
                    )
                    isRenewal = false
                }
                scheduleExpiresRenewal()
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
                val permanent = resp.statusCode in 400..499 && resp.statusCode != 401 && resp.statusCode != 407
                scheduleRetryOrFail(reason, permanent)
            }
        }
    }

    private suspend fun handleRequest(req: SipRequest) {
        when (req.method) {
            SipMethod.INVITE -> handleInvite(req)
            SipMethod.ACK -> handleAck(req)
            SipMethod.BYE -> handleBye(req)
            SipMethod.MESSAGE -> handleMessage(req)
            SipMethod.CANCEL -> handleCancel(req)
            SipMethod.SUBSCRIBE -> handleSubscribe(req)
            else -> Unit
        }
    }

    /** 5.14: Cancel ACK timeout when the platform's ACK lands. */
    private fun handleAck(ack: SipRequest) {
        val cid = ack.callId() ?: return
        if (cid == awaitingAckCallId) {
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            awaitingAckCallId = null
        }
    }

    /**
     * 5.15: Handle platform CANCEL (RFC 3261 §9).
     *
     * CANCEL aborts an INVITE that has not yet been finally answered. Our
     * INVITE handler currently answers synchronously with 200 OK before any
     * caller could realistically CANCEL, so by the time a CANCEL arrives the
     * dialog is already established — in that case we ignore (RFC 3261 says
     * to ack with 200 anyway). If a stream is active, also tear it down to be
     * safe (some platforms send CANCEL instead of BYE under error paths).
     */
    private suspend fun handleCancel(cancel: SipRequest) {
        try {
            val ok = com.uvp.sim.sip.SipBuilders.buildSimple200(cancel)
            transport.send(ok)
            _events.emit(SimEvent.MessageSent(ok))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send CANCEL 200: ${e.message}"))
        }
        val cid = cancel.callId() ?: ""
        val active = activeStream
        if (active != null && active.callId == cid) {
            stopActiveStream(cid, "remote CANCEL")
            if (_state.value == SipState.InCall) {
                _state.value = SipStateMachine.transition(_state.value, SipEvent.CallEnded)
            }
            _events.emit(SimEvent.CallEnded(cid, "remote CANCEL"))
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
        when (cmd) {
            "Catalog" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                sendCatalogResponse(sn)
            }
            "DeviceControl" -> handleDeviceControl(xml)
            "RecordInfo" -> handleRecordInfoQuery(xml)
            // Other CmdTypes (DeviceInfo, DeviceStatus, ...) deferred to later.
            else -> Unit
        }
    }

    /**
     * 5.13 / M2 §F.3 + M2 录像 — DeviceControl 子命令分发:
     *
     * 1. 走 [DeviceControlDispatcher] 处理 UI/3D 层关心的 10 项子命令(PTZ /
     *    Snapshot / Reboot 等),内部更新 [_deviceControlState] 让 SimulateScreen
     *    的 PtzHudPanel + Camera3DView 实时反映平台指令
     * 2. 同时单独处理 RecordCmd(dispatcher 只更新 UI 标志,这里调 recordingService
     *    真启停录像 + 回 MANSCDP Response,GB28181 §9.3 要求)
     */
    private suspend fun handleDeviceControl(xml: String) {
        // 路径 1:dispatcher 更新 UI / 3D 层状态
        deviceControlDispatcher.dispatch(xml)
        val lastCmd = _deviceControlState.value.lastCommand
        if (lastCmd != null) {
            _events.emit(
                SimEvent.DeviceControlReceived(
                    commandType = lastCmd.type,
                    detail = lastCmd.rawHex
                )
            )
        }
        // 路径 2:RecordCmd 真启停录像
        val recordCmd = com.uvp.sim.gb28181.ManscdpParser.recordCmd(xml) ?: return
        val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
        val deviceId = com.uvp.sim.gb28181.ManscdpParser.deviceId(xml) ?: config.device.deviceId
        var result = "OK"
        when (recordCmd.equals("Record", ignoreCase = true) to recordCmd.equals("StopRecord", ignoreCase = true)) {
            true to false -> {
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "平台下发 Record → 启动录像 source=PlatformCmd"
                )
                runCatching {
                    recordingService.start(
                        com.uvp.sim.recording.RecordSource.PlatformCmd,
                        config.device.videoChannelId
                    )
                }.onFailure {
                    SystemLogger.emit(LogLevel.Error, LogTag.Media, "RecordCmd 启动录像异常: ${it.message}")
                    result = "ERROR"
                }
            }
            false to true -> {
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "平台下发 StopRecord → 停止录像")
                runCatching { recordingService.stop() }
                    .onFailure {
                        SystemLogger.emit(LogLevel.Error, LogTag.Media, "RecordCmd 停止录像异常: ${it.message}")
                        result = "ERROR"
                    }
            }
            else -> {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "平台 RecordCmd 未识别 → '$recordCmd'"
                )
                result = "ERROR"
            }
        }
        sendDeviceControlResponse(sn = sn, deviceId = deviceId, result = result)
    }

    private suspend fun sendDeviceControlResponse(sn: String, deviceId: String, result: String) {
        val xmlBody = """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>DeviceControl</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<Result>$result</Result>
</Response>
""".replace("\n", "\r\n")
        runCatching {
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val callIdNow = callId ?: com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: com.uvp.sim.sip.SipBuilders.randomTag()
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
        }.onFailure {
            _events.emit(SimEvent.TransportError("send DeviceControl Response: ${it.message}"))
        }
    }

    private suspend fun handleRecordInfoQuery(xml: String) {
        val tz = "Asia/Shanghai"  // M2 默认本地时间(国标多用)
        val query = com.uvp.sim.gb28181.RecordInfoQuery.parse(xml, tz) ?: run {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "RecordInfo 查询解析失败")
            return
        }
        val files = recordingService.files.value
        val hits = files.filter {
            query.startMs <= it.endTimeMs && query.endMs >= it.startTimeMs &&
                (query.type == null || it.type == query.type)
        }
        val packets = com.uvp.sim.gb28181.RecordInfoNotify.buildAll(
            sn = query.sn,
            deviceId = config.device.deviceId,
            deviceName = config.device.name,
            items = hits,
            timeZoneId = tz
        )
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "平台查询录像 → 命中 ${hits.size} 条 / 分 ${packets.size} 包"
        )
        for (xmlBody in packets) {
            try {
                cseq += 1
                val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
                val callIdNow = callId ?: com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
                val fromTagNow = fromTag ?: com.uvp.sim.sip.SipBuilders.randomTag()
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
                _events.emit(SimEvent.TransportError("send RecordInfo: ${e.message}"))
            }
        }
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
        // Probe SDP s= line to decide Play vs Playback path
        val isPlayback = try {
            val offer = com.uvp.sim.sip.SdpPlaybackParser.parse(invite.body)
            offer.isPlayback
        } catch (_: Throwable) { false }

        if (isPlayback) {
            handlePlaybackInvite(invite)
            return
        }

        // 回放和直播各自有独立的 RtpSender / 端口 / SSRC,不抢摄像头硬件,可以并行。

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

        // 5.10: pick RTP transport mode from the SDP offer.
        // Platform offers passive → device connects out (TCP_ACTIVE).
        // Platform offers active  → device listens (TCP_PASSIVE).
        val rtpMode = when (offer.transport) {
            com.uvp.sim.sip.SdpTransport.UDP -> com.uvp.sim.network.RtpMode.UDP
            com.uvp.sim.sip.SdpTransport.TCP -> when (offer.tcpSetup) {
                com.uvp.sim.sip.SdpTcpSetup.PASSIVE -> com.uvp.sim.network.RtpMode.TCP_ACTIVE
                com.uvp.sim.sip.SdpTcpSetup.ACTIVE -> com.uvp.sim.network.RtpMode.TCP_PASSIVE
                com.uvp.sim.sip.SdpTcpSetup.ACTPASS -> com.uvp.sim.network.RtpMode.TCP_ACTIVE
            }
        }

        val rtp = sender(offer.remoteIp, offer.remotePort, rtpMode)
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
            sessionName = "Play",
            transport = offer.transport,
            tcpSetup = offer.tcpSetup
        )
        val deviceContact = "<sip:${config.device.deviceId}@$localIp:${localPortProvider()}>"
        val localToTag = com.uvp.sim.sip.SipBuilders.randomTag()
        val response = com.uvp.sim.sip.SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = deviceContact,
            toTag = localToTag,
            sdpBody = sdpAnswer
        )
        // Capture dialog routing for later device-initiated BYE
        val inviteFromHeader = invite.fromHeader() ?: ""
        val inviteToHeader = invite.toHeader() ?: ""
        val inviteContact = invite.firstHeader(com.uvp.sim.sip.SipHeader.CONTACT) ?: ""
        val remoteUri = parseUri(inviteFromHeader)
        val remoteTag = parseTag(inviteFromHeader)
        val localUri = parseUri(inviteToHeader)
        val remoteTarget = parseUri(inviteContact).ifEmpty { remoteUri }
        try {
            transport.send(response)
            _events.emit(SimEvent.MessageSent(response))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send 200 OK: ${e.message}"))
            try { rtp.close() } catch (_: Throwable) { }
            return
        }

        // 5.14: arm an ACK watchdog (RFC 3261 § 17.2.1 Timer H = 64*T1 = 32s)
        awaitingAckCallId = cid
        ackTimeoutJob?.cancel()
        ackTimeoutJob = scope.launch {
            delay(ACK_TIMEOUT_MS)
            if (awaitingAckCallId == cid) {
                _events.emit(SimEvent.InviteAckTimeout(cid))
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Lifecycle,
                    "INVITE 200 OK 未收到 ACK (${ACK_TIMEOUT_MS / 1000}s) — 平台可能已断开"
                )
                awaitingAckCallId = null
            }
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
            localUri = localUri,
            localTag = localToTag,
            remoteUri = remoteUri,
            remoteTag = remoteTag,
            remoteTarget = remoteTarget,
            statsJob = scope.launch {
                while (true) {
                    delay(MEDIA_STATS_INTERVAL_MS)
                    val a = activeStream ?: break
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "RTP 推送中: ${a.frameCount} 帧 / ${a.packetCount} 包"
                    )
                    _events.emit(
                        SimEvent.StreamStats(
                            callId = a.callId,
                            frameCount = a.frameCount,
                            packetCount = a.packetCount
                        )
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

        // 区分 BYE 走 activeStream 还是 activePlayback
        val playback = activePlayback
        if (playback != null && playback.callId == cid) {
            stopActivePlayback("remote BYE")
        } else {
            stopActiveStream(cid, "remote BYE")
        }
        _state.value = SipStateMachine.transition(_state.value, SipEvent.ByeReceived)
        _events.emit(SimEvent.CallEnded(cid, "remote BYE"))
    }

    private suspend fun handlePlaybackInvite(invite: SipRequest) {
        val cid = invite.callId() ?: ""
        // 直播 (activeStream) 和回放 (activePlayback) 用各自独立的 RtpSender + 端口 + SSRC,
        // 不共享摄像头硬件,可以并行。只在回放 vs 回放之间互斥(单一 demux/RTP session)。
        if (activePlayback != null) {
            sendBusyResponse(invite, "已有回放进行中")
            return
        }
        val builder = playbackBuilder
        if (builder == null) {
            // 没注入 PLAYBACK 能力,直接 487
            sendNotFoundResponse(invite, "PLAYBACK 能力未配置")
            return
        }
        val offer = try {
            com.uvp.sim.sip.SdpPlaybackParser.parse(invite.body)
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("PLAYBACK SDP parse: ${e.message}"))
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
            sequence = (cseq + 1) and 0x0FFF
        )

        val playback = builder.build(offer, segments, ssrc) ?: run {
            sendNotFoundResponse(invite, "PLAYBACK 构造失败")
            return
        }

        // 200 OK + answer SDP(s=Playback)
        val sdpAnswer = com.uvp.sim.sip.SdpAnswer.buildPlayAnswer(
            deviceId = config.device.deviceId,
            localIp = localIp,
            localRtpPort = playback.localRtpPort,
            ssrc = ssrc,
            sessionName = "Playback"
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
            _events.emit(SimEvent.TransportError("send PLAYBACK 200: ${e.message}"))
            playback.cancel()
            return
        }
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "开始回放 → ${segments.size} 段 spanMs=${offer.endMs - offer.startMs} ssrc=$ssrc " +
                "localRtpPort=${playback.localRtpPort} target=${offer.remoteIp}:${offer.remotePort}"
        )

        val job = scope.launch {
            try {
                playback.run()
                // 推完最后一帧 → 主动 BYE,然后关 RTP sink(顺序很重要,否则 RTP 流静默→BYE 空窗)
                sendBye(cid, ssrc, deviceContact, invite)
                runCatching { playback.cancel() }
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "回放完成 → 主动 BYE")
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("PLAYBACK error: ${e.message}"))
                SystemLogger.emit(LogLevel.Error, LogTag.Media, "回放异常: ${e.message}")
                runCatching { playback.cancel() }
            } finally {
                activePlayback = null
            }
        }
        activePlayback = ActivePlayback(
            callId = cid,
            ssrc = ssrc,
            playbackJob = job,
            rtpClose = playback::cancel
        )
    }

    private suspend fun stopActivePlayback(reason: String) {
        val pb = activePlayback ?: return
        activePlayback = null
        pb.playbackJob.cancel()
        try { pb.rtpClose() } catch (_: Throwable) { }
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "回放中断 → reason=$reason")
    }

    private suspend fun sendBusyResponse(req: SipRequest, reason: String) {
        runCatching {
            val resp = com.uvp.sim.sip.SipBuilders.buildSimpleResponse(
                req, statusCode = 486, reasonPhrase = "Busy Here",
                toTag = com.uvp.sim.sip.SipBuilders.randomTag()
            )
            transport.send(resp)
            _events.emit(SimEvent.MessageSent(resp))
        }
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "拒绝 INVITE → 486 ($reason)")
    }

    private suspend fun sendNotFoundResponse(req: SipRequest, reason: String) {
        runCatching {
            val resp = com.uvp.sim.sip.SipBuilders.buildSimpleResponse(
                req, statusCode = 487, reasonPhrase = "Request Terminated",
                toTag = com.uvp.sim.sip.SipBuilders.randomTag()
            )
            transport.send(resp)
            _events.emit(SimEvent.MessageSent(resp))
        }
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "拒绝 INVITE → 487 ($reason)")
    }

    /**
     * 主动 BYE — 回放推完最后一帧后调用,通知平台关闭对话框。
     * 简化实现:重用 invite 的 To/From,反向构造 BYE。M3 可补完整 dialog 跟踪。
     */
    private suspend fun sendBye(callId: String, ssrc: String, deviceContact: String, originalInvite: SipRequest) {
        runCatching {
            cseq += 1
            val byeReq = com.uvp.sim.sip.SipRequest(
                method = com.uvp.sim.sip.SipMethod.BYE,
                requestUri = originalInvite.requestUri,
                headers = listOf(
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.VIA,
                        "SIP/2.0/${config.transport.name} $localIp:${localPortProvider()};branch=${com.uvp.sim.sip.SipBuilders.randomBranch()}"),
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.FROM,
                        originalInvite.headers.firstOrNull { com.uvp.sim.sip.SipHeader.canonicalize(it.name) == com.uvp.sim.sip.SipHeader.TO }?.value
                            ?: "<sip:${config.device.deviceId}@${config.server.domain}>"),
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.TO,
                        originalInvite.headers.firstOrNull { com.uvp.sim.sip.SipHeader.canonicalize(it.name) == com.uvp.sim.sip.SipHeader.FROM }?.value
                            ?: "<sip:${config.server.serverId}@${config.server.domain}>"),
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.CALL_ID, callId),
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.CSEQ, "$cseq BYE"),
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.CONTACT, deviceContact),
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.MAX_FORWARDS, "70"),
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.USER_AGENT, config.userAgent)
                ),
                body = ByteArray(0)
            )
            transport.send(byeReq)
            _events.emit(SimEvent.MessageSent(byeReq))
        }
    }

    private suspend fun stopActiveStream(callId: String, reason: String) {
        val active = activeStream ?: return
        activeStream = null
        ackTimeoutJob?.cancel()
        ackTimeoutJob = null
        awaitingAckCallId = null
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

    private suspend fun handleSubscribe(req: SipRequest) {
        val intent = SubscribeHandler.parse(req, subscriptionRegistry.knownCallIds())
        when (intent) {
            is SubscribeIntent.NewSubscription -> {
                val toTag = SipBuilders.randomTag()
                val ok = SipBuilders.buildSubscribe200(req, toTag, intent.expiresSeconds)
                transport.send(ok)
                _events.emit(SimEvent.MessageSent(ok))

                val dialog = SubscriptionDialog(
                    kind = intent.kind,
                    subscriberUri = intent.subscriberUri,
                    callId = intent.callId,
                    fromTag = intent.fromTag,
                    toTag = toTag,
                    intervalSeconds = intent.intervalSeconds,
                    expiresSeconds = intent.expiresSeconds,
                    remainingSeconds = intent.expiresSeconds
                )
                subscriptionRegistry.activate(dialog) { d -> sendPositionNotify(d) }

                sendPositionNotify(dialog)

                _events.emit(SimEvent.SubscribeReceived(
                    subscriber = intent.subscriberUri,
                    kind = intent.kind,
                    expiresSeconds = intent.expiresSeconds,
                    intervalSeconds = intent.intervalSeconds
                ))
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Subscription,
                    "收到位置订阅: from=${intent.subscriberUri}, expires=${intent.expiresSeconds}s, interval=${intent.intervalSeconds}s"
                )
            }

            is SubscribeIntent.Refresh -> {
                val toTag = subscriptionRegistry.currentDialog(intent.callId)?.toTag
                    ?: SipBuilders.randomTag()
                val ok = SipBuilders.buildSubscribe200(req, toTag, intent.newExpiresSeconds)
                transport.send(ok)
                _events.emit(SimEvent.MessageSent(ok))
                subscriptionRegistry.refresh(intent.callId, intent.newExpiresSeconds)

                val d = subscriptionRegistry.currentDialog(intent.callId)
                _events.emit(SimEvent.SubscribeRefreshed(
                    subscriber = d?.subscriberUri ?: "",
                    newExpiresSeconds = intent.newExpiresSeconds
                ))
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Subscription,
                    "位置订阅已刷新: expires=${intent.newExpiresSeconds}s"
                )
            }

            is SubscribeIntent.Cancel -> {
                val d = subscriptionRegistry.currentDialog(intent.callId)
                val toTag = d?.toTag ?: SipBuilders.randomTag()
                val ok = SipBuilders.buildSubscribe200(req, toTag, 0, terminated = true)
                transport.send(ok)
                _events.emit(SimEvent.MessageSent(ok))
                subscriptionRegistry.cancel(intent.callId)

                _events.emit(SimEvent.SubscribeExpired(
                    subscriber = d?.subscriberUri ?: "",
                    kind = d?.kind ?: "MobilePosition"
                ))
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Subscription,
                    "位置订阅已取消: ${d?.subscriberUri}"
                )
            }

            is SubscribeIntent.Reject -> {
                val resp = SipResponse(
                    statusCode = intent.statusCode,
                    reasonPhrase = intent.reason,
                    headers = req.headers.filter {
                        val c = SipHeader.canonicalize(it.name)
                        c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.TO ||
                            c == SipHeader.CALL_ID || c == SipHeader.CSEQ
                    }
                )
                transport.send(resp)
                _events.emit(SimEvent.MessageSent(resp))
            }

            is SubscribeIntent.Ignored -> Unit
        }
    }

    private suspend fun sendPositionNotify(dialog: SubscriptionDialog) {
        notifySn++
        val fix = mockGps.next()
        val xml = MobilePositionNotify.build(
            deviceId = config.device.deviceId,
            sn = notifySn,
            point = fix.point,
            speed = fix.speed,
            direction = fix.direction,
            altitude = fix.altitude
        )
        val notifyCseq = dialog.cseqNotify + 1
        val remaining = dialog.remainingSeconds
        val ssValue = if (remaining > 0) "active;expires=$remaining" else "terminated"
        val notify = SipBuilders.buildNotify(
            subscriberUri = dialog.subscriberUri,
            callId = dialog.callId,
            fromTag = dialog.toTag,
            toTag = dialog.fromTag,
            event = "presence",
            subscriptionState = ssValue,
            cseq = notifyCseq,
            xmlBody = xml,
            localIp = localIp,
            localPort = localPortProvider(),
            transport = config.transport.name
        )
        try {
            transport.send(notify)
            _events.emit(SimEvent.MessageSent(notify))
            _events.emit(SimEvent.NotifySent(kind = dialog.kind, sn = notifySn))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    private fun startHeartbeat() {
        heartbeat?.stop()
        consecutiveKeepaliveTimeouts = 0
        lastKeepaliveAcked = true
        heartbeat = Heartbeat(
            intervalMillis = config.keepaliveIntervalSeconds * 1000L,
            scope = scope
        ) {
            if (!lastKeepaliveAcked) {
                consecutiveKeepaliveTimeouts++
                if (consecutiveKeepaliveTimeouts >= config.maxKeepaliveTimeouts) {
                    _events.emit(
                        SimEvent.HeartbeatTimeoutDetected(
                            consecutiveKeepaliveTimeouts,
                            config.maxKeepaliveTimeouts
                        )
                    )
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Lifecycle,
                        "心跳连续 ${consecutiveKeepaliveTimeouts} 次未响应,触发重注册"
                    )
                    triggerAutoReregister("heartbeat timeout ×${consecutiveKeepaliveTimeouts}")
                    return@Heartbeat
                }
            }
            lastKeepaliveAcked = false
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

    /**
     * 1.5: Auto re-register after heartbeat timeout.
     * Tears down current session (heartbeat + active stream) without sending
     * Unregister (platform is likely unreachable), then re-starts registration.
     */
    private fun triggerAutoReregister(reason: String) {
        scope.launch {
            _events.emit(SimEvent.AutoReregisterTriggered(reason))
            heartbeat?.stop()
            heartbeat = null
            renewalJob?.cancel()
            renewalJob = null
            activeStream?.let { active ->
                active.statsJob?.cancel()
                active.streamJob.cancel()
                active.audioJob?.cancel()
                try { active.rtpSender.close() } catch (_: Throwable) {}
                activeStream = null
            }
            mutex.withLock {
                _state.value = SipState.Disconnected
            }
            register()
        }
    }

    /**
     * 1.6: Schedule a REGISTER renewal before expires lapses.
     * Fires at 80% of expiresSeconds to give time for auth challenge round-trip.
     */
    private fun scheduleExpiresRenewal() {
        renewalJob?.cancel()
        val renewalDelayMs = (config.expiresSeconds * 800L)
        renewalJob = scope.launch {
            delay(renewalDelayMs)
            mutex.withLock {
                if (_state.value != SipState.Registered && _state.value != SipState.InCall) return@withLock
                isRenewal = true
                cseq += 1
                val branch = SipBuilders.randomBranch()
                val req = SipBuilders.buildRegister(
                    config, cseq, callId ?: SipBuilders.randomCallId(localIp),
                    branch, fromTag ?: SipBuilders.randomTag(), localIp, localPortProvider()
                )
                pendingRegister = req
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "Expires 续约: 发送 REGISTER(剩余 ${config.expiresSeconds * 200 / 1000}s)"
                )
                try {
                    transport.send(req)
                    _events.emit(SimEvent.MessageSent(req))
                    armRegisterTimeout()
                } catch (e: Throwable) {
                    isRenewal = false
                    _events.emit(SimEvent.TransportError("renewal send: ${e.message}"))
                }
            }
        }
    }

    companion object {
        const val REGISTER_TIMEOUT_MS: Long = 8_000L
        const val MEDIA_STATS_INTERVAL_MS: Long = 30_000L
        const val MAX_REGISTER_RETRIES: Int = 3
        const val INITIAL_RETRY_DELAY_MS: Long = 2_000L
        /** RFC 3261 § 17.2.1 Timer H = 64*T1 = 32s for ACK reception window. */
        const val ACK_TIMEOUT_MS: Long = 32_000L
    }
}

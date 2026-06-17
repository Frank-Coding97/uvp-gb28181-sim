package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.AlarmPayload
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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
                override suspend fun triggerSnapshotConfig(cfg: com.uvp.sim.gb28181.SnapShotConfig) {
                    val pipeline = snapshotPipeline
                    if (pipeline == null) {
                        SystemLogger.emit(
                            LogLevel.Warning,
                            LogTag.Lifecycle,
                            "SnapShotConfig 收到但抓拍管线未挂(平台壳未调 attachSnapshotPipeline);忽略 SessionID=${cfg.sessionId}"
                        )
                        return
                    }
                    SystemLogger.emit(
                        LogLevel.Info,
                        LogTag.Lifecycle,
                        "SnapShotConfig 派发 SessionID=${cfg.sessionId} N=${cfg.snapNum} interval=${cfg.intervalMs}ms"
                    )
                    pipeline.start(cfg)
                }
            },
            scope = scope
        )
    }

    private val mutex = Mutex()
    private var registerJob: Job? = null
    private var inboundJob: Job? = null
    private var heartbeat: Heartbeat? = null

    /**
     * 7.5 抓拍管线(GB-2022 §9.5)。Android 壳启动时通过 [attachSnapshotPipeline] 注入真实例,
     * 注入前 [DeviceControlActions.triggerSnapshotConfig] 收到 SnapShotConfig 直接 warn 忽略。
     */
    private var snapshotPipeline: com.uvp.sim.snapshot.SnapshotUploadEngine? = null
    private var snapshotCachePipeline: com.uvp.sim.snapshot.JpegLocalCache? = null

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
    private var catalogNotifySn = 0
    private var alarmNotifySn = 0

    // M2 Alarm: 本会话报警历史(最近 10 条,不持久化)
    private val alarmHistoryStore = AlarmHistoryStore()
    val alarmHistory: StateFlow<List<AlarmRecord>> = alarmHistoryStore.history

    /**
     * 当前生效的目录树。初始从 SimConfig.catalogTree 取(为空时用默认),
     * 用户在 UI 编辑器修改后通过 [updateCatalogTree] 写回。
     */
    private val _catalogTree = MutableStateFlow(CatalogTreeStore.effectiveTree(config))
    val catalogTree: StateFlow<List<com.uvp.sim.config.CatalogNode>> = _catalogTree.asStateFlow()

    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> = subscriptionRegistry.subscriptions

    /**
     * 当前推流通道的显示名 — OSD 通道名层烧戳用,跟随被叫通道(前置/后置)。
     * 初值为后置通道名(默认朝向 BACK),平台 INVITE 前置通道时切前置名。
     */
    private val _currentChannelName = MutableStateFlow(config.device.videoChannelName)
    val currentChannelName: StateFlow<String> = _currentChannelName.asStateFlow()

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
        val rtpClose: suspend () -> Unit,
        // M3: session 控制接口(setScale / pause / resume / seek)+ mode 标识
        val session: PlaybackSession? = null,
        val mode: MediaMode = MediaMode.PLAYBACK
    )

    enum class MediaMode { PLAYBACK, DOWNLOAD }

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
     * T10 (7.5 GB-2022) — Android 壳启动时挂上抓拍管线。注入前 SnapShotConfig 命令会被忽略。
     *
     * @param capture 抓帧端口(Android = CameraX ImageCapture, iOS = stub)
     * @param cache JPEG 落盘端口
     * @param httpClient ktor HttpClient(Android = CIO engine)
     */
    fun attachSnapshotPipeline(
        capture: com.uvp.sim.snapshot.SnapshotCapture,
        cache: com.uvp.sim.snapshot.JpegLocalCache,
        httpClient: io.ktor.client.HttpClient
    ) {
        snapshotCachePipeline = cache
        val uploader = com.uvp.sim.snapshot.SnapshotHttpUploader(httpClient)
        snapshotPipeline = com.uvp.sim.snapshot.SnapshotUploadEngine(
            takeJpeg = { capture.takeJpeg() },
            writeCache = { id, bytes -> cache.write(id, bytes) },
            uploader = uploader,
            notifySender = { xml -> sendSnapshotNotify(xml) },
            scope = scope,
            deviceId = config.device.deviceId,
            snAllocator = {
                cseq += 1
                cseq.toString()
            },
            onProgress = { progress ->
                scope.launch {
                    when (progress) {
                        is com.uvp.sim.snapshot.SnapshotProgress.NotifySent ->
                            _events.emit(
                                SimEvent.SnapshotUploaded(
                                    sessionId = progress.sessionId,
                                    snapShotId = progress.snapShotId,
                                    count = progress.count,
                                    total = progress.total
                                )
                            )
                        is com.uvp.sim.snapshot.SnapshotProgress.UploadFailedFinal ->
                            _events.emit(
                                SimEvent.SnapshotUploadFailed(
                                    sessionId = progress.sessionId,
                                    snapShotId = progress.snapShotId
                                )
                            )
                        is com.uvp.sim.snapshot.SnapshotProgress.CaptureSkipped -> {
                            // 仅日志,不发 SimEvent(无信令影响)
                            SystemLogger.emit(
                                LogLevel.Warning,
                                LogTag.Media,
                                "snapshot capture returned null: SessionID=${progress.sessionId} ID=${progress.snapShotId}"
                            )
                        }
                    }
                }
            }
        )
    }

    /**
     * T10 (7.5) — 把 SnapShotNotifyBuilder 已构造好的 XML 包裹成 SIP MESSAGE 发出。
     * 复用 [reportSnapshot] 的出站结构(buildMessage + transport.send)。
     */
    private suspend fun sendSnapshotNotify(xml: String) {
        mutex.withLock {
            if (_state.value != SipState.Registered && _state.value != SipState.InCall) {
                _events.emit(SimEvent.TransportError("snapshot notify: not registered"))
                return
            }
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
                xmlBody = xml
            )
            try {
                transport.send(msg)
                _events.emit(SimEvent.MessageSent(msg))
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("snapshot notify send: ${e.message}"))
            }
        }
    }

    /**
     * M2 Alarm — 主动发起报警(主屏一键 / 能力页详细编辑后)。
     *
     * fan-out 两路,body 完全相同(都是 [com.uvp.sim.gb28181.AlarmNotify.buildAlarm]):
     *   路 A — MESSAGE 给注册中心(跟 reportSnapshot 同 SN 池 = cseq)
     *   路 B — NOTIFY 给每个活跃 Alarm 订阅人(失败不中断其他订阅人)
     *
     * 完成后:append 历史 + 翻 isAlarming=true + emit AlarmFired(+每条 NOTIFY emit AlarmNotifySent)。
     */
    suspend fun reportAlarm(payload: AlarmPayload) {
        val dialogs: List<SubscriptionDialog>
        val sn: String
        mutex.withLock {
            if (_state.value != SipState.Registered && _state.value != SipState.InCall) {
                _events.emit(SimEvent.TransportError("alarm: not registered"))
                return
            }
            cseq += 1
            sn = cseq.toString()
            val body = com.uvp.sim.gb28181.AlarmNotify.buildAlarm(config, sn, payload)

            // 路 A — MESSAGE 给注册中心
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
                xmlBody = body
            )
            try {
                transport.send(msg)
                _events.emit(SimEvent.MessageSent(msg))
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("alarm MESSAGE send: ${e.message}"))
            }

            // 路 B — NOTIFY 给每个活跃 Alarm 订阅人(在锁内拿 dialog 快照,锁外发)
            dialogs = subscriptionRegistry.dialogsByKind("Alarm")
            for (d in dialogs) {
                val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
                sendAlarmNotify(updated, body, sn)
            }
        }

        alarmHistoryStore.append(
            AlarmRecord(payload = payload, firedAtMs = nowMs(), notifiedSubscribers = dialogs.size)
        )
        _deviceControlState.update { it.copy(isAlarming = true) }
        _events.emit(SimEvent.AlarmFired(payload.type, payload.priority, payload.description))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "主动报警 → MESSAGE + ${dialogs.size} 订阅 NOTIFY · ${payload.type.label}/${payload.priority.label}"
        )
    }

    /**
     * M2 Alarm — 用户本地复位(主屏报警中 tile 确认 / 子页复位按钮)。
     * 仅翻 isAlarming=false + emit AlarmReset(Local),**不走 SIP**(spec S4),
     * 也不推 NOTIFY(本地操作不通知订阅人)。
     */
    suspend fun localResetAlarm() {
        _deviceControlState.update { it.copy(isAlarming = false) }
        _events.emit(SimEvent.AlarmReset(SimEvent.ResetSource.Local))
        SystemLogger.emit(LogLevel.Info, LogTag.Network, "本地复位报警(不走 SIP)")
    }

    /** 给单个 Alarm 订阅 dialog 发一条 NOTIFY(body 跟 MESSAGE 一致)。 */
    private suspend fun sendAlarmNotify(dialog: SubscriptionDialog, body: String, sn: String) {
        alarmNotifySn++
        val notifyCseq = dialog.cseqNotify + 1
        val remaining = dialog.remainingSeconds
        val ssValue = if (remaining > 0) "active;expires=$remaining" else "terminated"
        val notify = SipBuilders.buildNotify(
            subscriberUri = dialog.subscriberUri,
            callId = dialog.callId,
            fromTag = dialog.toTag,
            toTag = dialog.fromTag,
            event = "presence",  // WVP 报警订阅走 Event: presence(同 Catalog),NOTIFY 须匹配
            subscriptionState = ssValue,
            cseq = notifyCseq,
            xmlBody = body,
            localIp = localIp,
            localPort = localPortProvider(),
            transport = config.transport.name,
            userAgent = config.userAgent
        )
        try {
            transport.send(notify)
            _events.emit(SimEvent.MessageSent(notify))
            _events.emit(SimEvent.AlarmNotifySent(sn = sn, subscriber = dialog.subscriberUri))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send Alarm NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    /**
     * M2 Alarm 反向闭环 — 平台 AlarmCmd 复位后,给每个活跃 Alarm 订阅人推一条
     * 「报警已复位」NOTIFY(spec Q2:复位也是报警状态变化的一种)。
     */
    private suspend fun pushAlarmResetNotify(by: String?) {
        val dialogs = subscriptionRegistry.dialogsByKind("Alarm")
        if (dialogs.isEmpty()) return
        cseq += 1
        val sn = cseq.toString()
        val resetPayload = AlarmPayload(
            deviceId = config.device.alarmChannelId,
            description = "报警已复位 (AlarmCmd by ${by ?: "platform"})"
        )
        val body = com.uvp.sim.gb28181.AlarmNotify.buildAlarm(config, sn, resetPayload)
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            sendAlarmNotify(updated, body, sn)
        }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun subscriptionLabel(kind: String?): String = when (kind) {
        "Catalog" -> "目录"
        "Alarm" -> "报警"
        else -> "位置"
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

    /** Extract user-part from a `sip:user@host` URI; falls back to the platform serverId. */
    private fun parseUriUser(uri: String): String {
        val s = uri.substringAfter("sip:", uri).substringBefore('@', "")
        return s.ifEmpty { config.server.serverId }
    }

    /**
     * 用当前 [config.video] 构造 GB28181 § C.2 `f=` 媒体描述符。
     * EasyCVR / LiveGBS 等平台会读这个字段挑解码器。
     */
    private fun buildSdpMediaSpec(): com.uvp.sim.sip.SdpAnswer.MediaSpec {
        val v = config.video
        val videoCodec = when (v.videoCodec) {
            com.uvp.sim.media.VideoCodec.H264 -> 2
            com.uvp.sim.media.VideoCodec.H265 -> 5
        }
        val resolution = when (v.resolution) {
            com.uvp.sim.config.VideoResolution.SD_480P -> 4   // D1
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
            audioSampleRate = audioSampleRate
        )
    }

    /**
     * 从 INVITE 提取被叫 channelId(国标 20 位编码)。
     * 优先从 Request-URI(`sip:<channelId>@host:port`)取 user part,
     * 退化到 To 头。提不出来返回空串。
     */
    private fun extractInviteTarget(invite: SipRequest): String {
        // requestUri 形如 "sip:34020000001320000001@192.168.10.50:5060"
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

    /**
     * P1-2: 按 channelId 类型决定是否拒绝 INVITE。
     * 返回 null 表示放行,非 null 是 (statusCode, reasonPhrase) 拒绝原因。
     *
     * 判定规则:
     *  - 在当前 _catalogTree 找该 channelId 的节点
     *  - 视频通道 (132): 放行(走原 INVITE 链路)
     *  - 报警通道 (134): 488 — 报警通道不应用于 RTP 流(GB §10)
     *  - 容器节点 Device/137/138: 488 — 不可对设备根/分组/虚组织发起 INVITE
     *  - 找不到的 channelId: 放行(向后兼容,保留 SimConfig.device.videoChannelId 等)
     */
    private fun classifyInviteTarget(channelId: String): Pair<Int, String>? {
        if (channelId.isBlank()) return null
        val node = _catalogTree.value.firstOrNull { it.id == channelId } ?: return null
        return when (node.type) {
            com.uvp.sim.config.CatalogNodeType.VideoChannel -> null
            com.uvp.sim.config.CatalogNodeType.AlarmChannel ->
                488 to "Not Acceptable Here (alarm channel does not stream)"
            com.uvp.sim.config.CatalogNodeType.Device ->
                488 to "Not Acceptable Here (cannot invite device root)"
            com.uvp.sim.config.CatalogNodeType.BusinessGroup ->
                488 to "Not Acceptable Here (cannot invite business group)"
            com.uvp.sim.config.CatalogNodeType.VirtualOrg ->
                488 to "Not Acceptable Here (cannot invite virtual org)"
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
                // Break the 401 storm: a single REGISTER can be retransmitted (UDP) and the
                // platform answers each copy with its own fresh-nonce 401. If we already
                // responded to a challenge (pending now carries Authorization), re-sending
                // again would spawn one new REGISTER per 401 → exponential blast → the
                // platform rate-limits us ("register N times in 3 seconds") and the device
                // never truly registers. Respond to the *first* challenge only; ignore the
                // rest of the storm and let the watchdog/retry path handle a genuine failure.
                val alreadyAuthed = pending.firstHeader(SipHeader.AUTHORIZATION) != null
                if (alreadyAuthed) {
                    SystemLogger.emit(
                        LogLevel.Debug, LogTag.Lifecycle,
                        "忽略重复 401(已应答挑战,等待 200 或超时)"
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
            SipMethod.INFO -> handleInfo(req)
            else -> Unit
        }
    }

    /**
     * Handle SIP INFO request (M3 §9.7.2 PLAY/PAUSE/Range/Scale via MANSRTSP).
     *
     * 流程:
     *   1. 检查 activePlayback 是否存在 → 不存在 481
     *   2. MansRtspParser.parse → 失败 400
     *   3. 200 OK 先发(spec §B.6 不阻塞)
     *   4. 异步执行 setScale / pause / resume / seek / teardown
     */
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

        // 200 OK 必须先发,然后异步执行控制(spec §B.6)
        sendSimpleResponse(req, 200, "OK")

        val session = pb.session
        scope.launch {
            when (cmd) {
                is com.uvp.sim.sip.MansRtspCommand.Play -> {
                    cmd.scale?.let { session?.setScale(it) }
                    val rangeMs = cmd.rangeStartMs
                    if (rangeMs != null) {
                        session?.seek(rangeMs)
                    } else {
                        session?.resume()
                    }
                }
                is com.uvp.sim.sip.MansRtspCommand.Pause -> session?.pause()
                is com.uvp.sim.sip.MansRtspCommand.Teardown -> stopActivePlayback("INFO TEARDOWN")
            }
        }
    }

    /** 通用应答 - 200 / 400 / 481 等单行响应。 */
    private suspend fun sendSimpleResponse(req: SipRequest, statusCode: Int, reasonPhrase: String) {
        runCatching {
            val resp = com.uvp.sim.sip.SipBuilders.buildSimpleResponse(
                req,
                statusCode = statusCode,
                reasonPhrase = reasonPhrase,
                toTag = com.uvp.sim.sip.SipBuilders.randomTag()
            )
            transport.send(resp)
            _events.emit(SimEvent.MessageSent(resp))
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
            val ok = com.uvp.sim.sip.SipBuilders.buildSimple200(cancel, userAgent = config.userAgent)
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
                message, toTag = com.uvp.sim.sip.SipBuilders.randomTag(),
                userAgent = config.userAgent
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
            "DeviceInfo" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                sendDeviceInfoResponse(sn)
            }
            "DeviceStatus" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                sendDeviceStatusResponse(sn)
            }
            "PresetQuery" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                val channelId = com.uvp.sim.gb28181.ManscdpParser.deviceId(xml) ?: ""
                sendPresetQueryResponse(sn, channelId)
            }
            "ConfigDownload" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                val types = com.uvp.sim.gb28181.ConfigDownloadResponse.parseConfigTypes(xml)
                sendConfigDownloadResponse(sn, types)
            }
            "MobilePosition" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                sendMobilePositionResponse(sn)
            }
            "DeviceControl" -> handleDeviceControl(xml, fromUri = message.fromHeader()?.let { parseUri(it) })
            "RecordInfo" -> handleRecordInfoQuery(xml)
            "Broadcast" -> handleBroadcast(xml, fromUri = message.fromHeader()?.let { parseUri(it) })
            // 其它 CmdType(SVAC*、AlarmStatusQuery 等)暂不处理。
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
    private suspend fun handleDeviceControl(xml: String, fromUri: String? = null) {
        // 路径 1:dispatcher 更新 UI / 3D 层状态,返回 ack
        val ack = deviceControlDispatcher.dispatch(xml, fromUri = fromUri)
        val lastCmd = _deviceControlState.value.lastCommand
        if (lastCmd != null) {
            _events.emit(
                SimEvent.DeviceControlReceived(
                    commandType = lastCmd.type,
                    detail = lastCmd.rawHex
                )
            )
        }
        // M2 Alarm 反向闭环:AlarmCmd 复位 → emit AlarmReset(Remote) + 推「报警已复位」NOTIFY
        if (ack.alarmReset) {
            _events.emit(SimEvent.AlarmReset(SimEvent.ResetSource.Remote(ack.by ?: "platform")))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台 AlarmCmd 复位报警 by=${ack.by ?: "platform"}"
            )
            pushAlarmResetNotify(ack.by)
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

    /**
     * GB/T 28181 §9.8 / §F.2.1 语音广播下行(平台喊话设备)。
     *
     * 200 OK 已在 [handleMessage] 顶部统一发出。这里:
     *   1. 解析 SourceID / TargetID / SN
     *   2. 校验 TargetID == 本机 deviceId(不匹配 → Broadcast Response ERROR)
     *   3. 匹配 → Broadcast Response OK + emit BroadcastReceived
     *
     * T1 阶段到此为止(不发反向 INVITE)。后续 task 在匹配分支末尾接 sendBroadcastInvite。
     */
    private suspend fun handleBroadcast(xml: String, fromUri: String? = null) {
        val query = com.uvp.sim.gb28181.BroadcastQuery.parse(xml)
        val sn = query.sn ?: "0"
        val myId = config.device.deviceId
        val targetId = query.targetId
        if (targetId != myId) {
            sendBroadcastResponseMessage(
                com.uvp.sim.gb28181.BroadcastResponse.build(
                    deviceId = myId, sn = sn,
                    result = com.uvp.sim.gb28181.BroadcastResponse.Result.ERROR,
                    reason = "target mismatch"
                )
            )
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "语音广播 TargetID 不匹配: 收到 '$targetId' 期望 '$myId' → ERROR"
            )
            return
        }
        sendBroadcastResponseMessage(
            com.uvp.sim.gb28181.BroadcastResponse.build(
                deviceId = myId, sn = sn,
                result = com.uvp.sim.gb28181.BroadcastResponse.Result.OK
            )
        )
        _events.emit(SimEvent.BroadcastReceived(sourceId = query.sourceId ?: "", targetId = myId))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "收到语音广播请求 source=${query.sourceId} → 已回 Broadcast Response OK"
        )
    }

    /** 把已构造的 Broadcast Response MANSCDP body 包成 MESSAGE 发给平台(第二条,200 OK 之外)。 */
    private suspend fun sendBroadcastResponseMessage(xmlBody: String) {
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
            _events.emit(SimEvent.TransportError("send Broadcast Response: ${it.message}"))
        }
    }

    private suspend fun handleRecordInfoQuery(xml: String) {        val tz = "Asia/Shanghai"  // M2 默认本地时间(国标多用)
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
            // M2: Query 响应跟 NOTIFY 共用同一棵 catalog tree,保证 WVP 看到的总数一致
            val xmlBody = com.uvp.sim.gb28181.CatalogResponse.buildFromTree(
                config = config,
                sn = sn,
                tree = _catalogTree.value
            )
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

    private suspend fun sendDeviceInfoResponse(sn: String) {
        try {
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val callIdNow = callId ?: com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: com.uvp.sim.sip.SipBuilders.randomTag()
            val xmlBody = com.uvp.sim.gb28181.DeviceInfoResponse.build(config, sn)
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
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台查询 DeviceInfo → 已应答 sn=$sn"
            )
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send DeviceInfo response: ${e.message}"))
        }
    }

    private suspend fun sendDeviceStatusResponse(sn: String) {
        try {
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val callIdNow = callId ?: com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: com.uvp.sim.sip.SipBuilders.randomTag()
            val ctrl = _deviceControlState.value
            val snapshot = com.uvp.sim.gb28181.DeviceStatusSnapshot(
                online = _state.value == com.uvp.sim.sip.SipState.Registered ||
                    _state.value == com.uvp.sim.sip.SipState.InCall,
                deviceTime = currentLocalIso(),
                recording = ctrl.isRecording,
                alarming = ctrl.isAlarming,
                guarded = ctrl.isGuarded
            )
            val xmlBody = com.uvp.sim.gb28181.DeviceStatusResponse.build(config, sn, snapshot)
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
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台查询 DeviceStatus → 已应答 sn=$sn online=${snapshot.online} record=${snapshot.recording} alarm=${snapshot.alarming}"
            )
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send DeviceStatus response: ${e.message}"))
        }
    }

    /** 设备本地时间 ISO8601 无偏移,GB28181 默认格式 "yyyy-MM-ddTHH:mm:ss" */
    private fun currentLocalIso(): String {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        val ldt = now.toLocalDateTime(tz)
        return buildString {
            append(ldt.year.toString().padStart(4, '0'))
            append('-')
            append(ldt.monthNumber.toString().padStart(2, '0'))
            append('-')
            append(ldt.dayOfMonth.toString().padStart(2, '0'))
            append('T')
            append(ldt.hour.toString().padStart(2, '0'))
            append(':')
            append(ldt.minute.toString().padStart(2, '0'))
            append(':')
            append(ldt.second.toString().padStart(2, '0'))
        }
    }

    private suspend fun sendPresetQueryResponse(sn: String, channelId: String) {
        try {
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val callIdNow = callId ?: com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: com.uvp.sim.sip.SipBuilders.randomTag()
            val xmlBody = com.uvp.sim.gb28181.PresetQueryResponse.build(config, sn, channelId)
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
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台查询 PresetQuery → 已应答(空清单)sn=$sn"
            )
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send PresetQuery response: ${e.message}"))
        }
    }

    private suspend fun sendConfigDownloadResponse(sn: String, configTypes: List<String>) {
        try {
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val callIdNow = callId ?: com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: com.uvp.sim.sip.SipBuilders.randomTag()
            val xmlBody = com.uvp.sim.gb28181.ConfigDownloadResponse.build(config, sn, configTypes)
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
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台查询 ConfigDownload → 已应答 sn=$sn types=${configTypes.joinToString("/")}"
            )
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send ConfigDownload response: ${e.message}"))
        }
    }

    /**
     * 3.7 / §9.5.4 MobilePosition 单次查询应答。
     * 区别于 8.3 的 SUBSCRIBE 周期 NOTIFY,这是 MESSAGE 单次 Response wrapper,
     * 从 [mockGps] 取一条最新 fix 即返。
     */
    private suspend fun sendMobilePositionResponse(sn: String) {
        try {
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val callIdNow = callId ?: com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: com.uvp.sim.sip.SipBuilders.randomTag()
            val fix = mockGps.next()
            val xmlBody = com.uvp.sim.gb28181.MobilePositionResponse.build(
                deviceId = config.device.deviceId,
                sn = sn,
                point = fix.point,
                speed = fix.speed,
                direction = fix.direction,
                altitude = fix.altitude,
                timestamp = currentLocalIso()
            )
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
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台查询 MobilePosition → 已应答 sn=$sn lng=${fix.point.longitude} lat=${fix.point.latitude}"
            )
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send MobilePosition response: ${e.message}"))
        }
    }

    private suspend fun handleInvite(invite: SipRequest) {
        // M2 P1-2: 按 INVITE 目标 channelId 类型路由 — 不支持的类型立即 488
        // 放在最前 — 报警/目录节点的 INVITE 不应走任何后续路径(Play 或 Playback)
        val channelId = extractInviteTarget(invite)
        val rejection = classifyInviteTarget(channelId)
        if (rejection != null) {
            try {
                val resp = SipBuilders.buildSimpleError(
                    request = invite,
                    statusCode = rejection.first,
                    reasonPhrase = rejection.second,
                    toTag = SipBuilders.randomTag()
                )
                transport.send(resp)
                _events.emit(SimEvent.MessageSent(resp))
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "拒绝 INVITE: channelId=$channelId → ${rejection.first} ${rejection.second}"
                )
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("send INVITE reject: ${e.message}"))
            }
            return
        }

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

        // 双真实通道(B 方案):同一时刻只允许一路真实直播流(手机硬件不支持前后置并发)。
        // 已有活跃直播流时,对任意视频通道的 INVITE 一律 486 Busy Here,不抢占正在推的流。
        // 必须在 state transition / early-return 之前,既保证状态机不被第二路扰动,也保证单测可断言。
        if (activeStream != null) {
            sendBusyResponse(invite, "已有直播流推送中,拒绝并发第二路")
            return
        }

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

        // 双真实通道:据被叫 channelId 映射切摄像头朝向(前置/后置)。无运行相机时为空操作。
        cam.setFacing(config.device.facingForChannel(channelId))
        // OSD 通道名跟随当前推流通道,反映到下一帧渲染。
        _currentChannelName.value = config.device.channelNameForChannel(channelId)

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
            val cls = e::class.simpleName ?: "?"
            val msg = e.message ?: "<null>"
            val cause = e.cause?.let { "${it::class.simpleName}: ${it.message}" } ?: "<no cause>"
            _events.emit(SimEvent.TransportError(
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
            mediaSpec = buildSdpMediaSpec()
        )
        val deviceContact = "<sip:${config.device.deviceId}@$localIp:${localPortProvider()}>"
        val localToTag = com.uvp.sim.sip.SipBuilders.randomTag()
        val inviteFromUser = parseUriUser(parseUri(invite.fromHeader() ?: ""))
        val response = com.uvp.sim.sip.SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = deviceContact,
            toTag = localToTag,
            sdpBody = sdpAnswer,
            userAgent = config.userAgent,
            subject = com.uvp.sim.sip.SipBuilders.subject(
                senderId = config.device.deviceId,
                ssrc = ssrc,
                receiverId = inviteFromUser
            )
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
                            // 单次 send 失败即整路终止 — TCP broken pipe 等不可恢复错误
                            // 必须停掉,否则下一帧再写再抛,日志风暴 + 抢锁拖垮 audio 线程
                            rtp.send(p)
                            activeStream?.let { it.packetCount += 1 }
                        }
                    }
                    activeStream?.let { it.frameCount += 1 }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("RTP video send: ${e.message}"))
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
                                activeStream?.let { it.packetCount += 1 }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _events.emit(SimEvent.TransportError("RTP audio send: ${e.message}"))
                    scope.launch { stopActiveStream(cid, "audio send failed: ${e.message}") }
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
            val ack = com.uvp.sim.sip.SipBuilders.buildSimple200(bye, userAgent = config.userAgent)
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

        // 200 OK + answer SDP(s=Playback / s=Download)
        val sessionMode = if (offer.isDownload) MediaMode.DOWNLOAD else MediaMode.PLAYBACK
        val sessionName = if (offer.isDownload) "Download" else "Playback"
        val sdpAnswer = com.uvp.sim.sip.SdpAnswer.buildPlayAnswer(
            deviceId = config.device.deviceId,
            localIp = localIp,
            localRtpPort = playback.localRtpPort,
            ssrc = ssrc,
            sessionName = sessionName,
            mediaSpec = buildSdpMediaSpec()
        )
        val deviceContact = "<sip:${config.device.deviceId}@$localIp:${localPortProvider()}>"
        val pbInviteFromUser = parseUriUser(parseUri(invite.fromHeader() ?: ""))
        val response = com.uvp.sim.sip.SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = deviceContact,
            toTag = com.uvp.sim.sip.SipBuilders.randomTag(),
            sdpBody = sdpAnswer,
            userAgent = config.userAgent,
            subject = com.uvp.sim.sip.SipBuilders.subject(
                senderId = config.device.deviceId,
                ssrc = ssrc,
                receiverId = pbInviteFromUser
            )
        )
        try {
            transport.send(response)
            _events.emit(SimEvent.MessageSent(response))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send ${sessionName.uppercase()} 200: ${e.message}"))
            playback.cancel()
            return
        }
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "${if (offer.isDownload) "DOWNLOAD_START" else "开始回放"} → ${segments.size} 段 spanMs=${offer.endMs - offer.startMs} ssrc=$ssrc " +
                "localRtpPort=${playback.localRtpPort} target=${offer.remoteIp}:${offer.remotePort}" +
                if (offer.isDownload) " downloadSpeed=${offer.downloadSpeed}" else ""
        )

        // M3 §D Download:启动前注入下载倍速到 PlaybackEngine
        if (offer.isDownload && offer.downloadSpeed != 1.0) {
            playback.setScale(offer.downloadSpeed)
        }

        val job = scope.launch {
            try {
                playback.run()
                // M3 §D Download 完成 → 先发 MediaStatusNotify(NotifyType=121),再 BYE
                if (offer.isDownload) {
                    sendMediaStatusNotify()
                    SystemLogger.emit(LogLevel.Info, LogTag.Media, "DOWNLOAD_COMPLETE → MediaStatus Notify 已发")
                }
                // 推完最后一帧 → 主动 BYE,然后关 RTP sink(顺序很重要,否则 RTP 流静默→BYE 空窗)
                sendBye(cid, ssrc, deviceContact, invite)
                runCatching { playback.cancel() }
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "${if (offer.isDownload) "下载完成" else "回放完成"} → 主动 BYE"
                )
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("${sessionName.uppercase()} error: ${e.message}"))
                SystemLogger.emit(LogLevel.Error, LogTag.Media, "${sessionName} 异常: ${e.message}")
                runCatching { playback.cancel() }
            } finally {
                activePlayback = null
            }
        }
        activePlayback = ActivePlayback(
            callId = cid,
            ssrc = ssrc,
            playbackJob = job,
            rtpClose = playback::cancel,
            session = playback,
            mode = sessionMode
        )
    }

    /**
     * M3 §D 录像下载完成 → 发 MediaStatusNotify (NotifyType=121)。
     * 不强等 200 OK,失败仅日志(plan §8.2)。
     */
    private suspend fun sendMediaStatusNotify() {
        runCatching {
            cseq += 1
            notifySn += 1
            val req = com.uvp.sim.sip.MediaStatusNotify.build(
                config = config,
                cseq = cseq,
                callId = com.uvp.sim.sip.SipBuilders.randomCallId(localIp),
                branch = com.uvp.sim.sip.SipBuilders.randomBranch(),
                fromTag = com.uvp.sim.sip.SipBuilders.randomTag(),
                localIp = localIp,
                localPort = localPortProvider(),
                sn = notifySn
            )
            transport.send(req)
            _events.emit(SimEvent.MessageSent(req))
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "MediaStatus Notify 发送失败: ${it.message}(BYE 仍发)"
            )
        }
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
                toTag = com.uvp.sim.sip.SipBuilders.randomTag(),
                userAgent = config.userAgent
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
                toTag = com.uvp.sim.sip.SipBuilders.randomTag(),
                userAgent = config.userAgent
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
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.USER_AGENT, config.userAgent),
                    com.uvp.sim.sip.SipMessage.Header(com.uvp.sim.sip.SipHeader.DATE, com.uvp.sim.sip.SipBuilders.rfc1123Date())
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
                val ok = SipBuilders.buildSubscribe200(req, toTag, intent.expiresSeconds, userAgent = config.userAgent)
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
                // Catalog / Alarm: SubscriptionRegistry 不会启 Heartbeat,但仍 activate 以维护 expiry
                // MobilePosition: SubscriptionRegistry 启 Heartbeat 周期推
                subscriptionRegistry.activate(
                    dialog,
                    onExpire = { d ->
                        if (d.kind == "Alarm") {
                            _events.emit(SimEvent.AlarmSubscriptionExpired(subscriber = d.subscriberUri))
                            SystemLogger.emit(
                                LogLevel.Info, LogTag.Subscription,
                                "报警订阅自然过期: ${d.subscriberUri}"
                            )
                        }
                    }
                ) { d ->
                    when (d.kind) {
                        "Catalog" -> sendCatalogNotify(d)
                        "Alarm" -> Unit  // Alarm 事件驱动,无周期 NOTIFY(reportAlarm 时才推)
                        else -> sendPositionNotify(d)
                    }
                }

                // initial NOTIFY:Catalog/Position 立即推一次,Alarm 不发(报警是事件流,无全集)
                when (intent.kind) {
                    "Catalog" -> sendCatalogNotify(dialog)
                    "Alarm" -> Unit
                    else -> sendPositionNotify(dialog)
                }

                if (intent.kind == "Alarm") {
                    _events.emit(SimEvent.AlarmSubscribed(
                        subscriber = intent.subscriberUri,
                        expires = intent.expiresSeconds
                    ))
                } else {
                    _events.emit(SimEvent.SubscribeReceived(
                        subscriber = intent.subscriberUri,
                        kind = intent.kind,
                        expiresSeconds = intent.expiresSeconds,
                        intervalSeconds = intent.intervalSeconds
                    ))
                }
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Subscription,
                    "收到${subscriptionLabel(intent.kind)}订阅: from=${intent.subscriberUri}, expires=${intent.expiresSeconds}s, interval=${intent.intervalSeconds}s"
                )
            }

            is SubscribeIntent.Refresh -> {
                val toTag = subscriptionRegistry.currentDialog(intent.callId)?.toTag
                    ?: SipBuilders.randomTag()
                val ok = SipBuilders.buildSubscribe200(req, toTag, intent.newExpiresSeconds, userAgent = config.userAgent)
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
                    "${subscriptionLabel(d?.kind)}订阅已刷新: expires=${intent.newExpiresSeconds}s"
                )
            }

            is SubscribeIntent.Cancel -> {
                val d = subscriptionRegistry.currentDialog(intent.callId)
                val toTag = d?.toTag ?: SipBuilders.randomTag()
                val ok = SipBuilders.buildSubscribe200(req, toTag, 0, terminated = true, userAgent = config.userAgent)
                transport.send(ok)
                _events.emit(SimEvent.MessageSent(ok))
                subscriptionRegistry.cancel(intent.callId)

                if (d?.kind == "Alarm") {
                    _events.emit(SimEvent.AlarmSubscriptionExpired(subscriber = d.subscriberUri))
                } else {
                    _events.emit(SimEvent.SubscribeExpired(
                        subscriber = d?.subscriberUri ?: "",
                        kind = d?.kind ?: "MobilePosition"
                    ))
                }
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Subscription,
                    "${subscriptionLabel(d?.kind)}订阅已取消: ${d?.subscriberUri}"
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

    private suspend fun sendCatalogNotify(dialog: SubscriptionDialog) {
        catalogNotifySn++
        val xml = com.uvp.sim.gb28181.CatalogNotifyBuilder.build(
            deviceId = config.device.deviceId,
            sn = catalogNotifySn,
            tree = _catalogTree.value
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
            _events.emit(SimEvent.NotifySent(kind = dialog.kind, sn = catalogNotifySn))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send Catalog NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    /**
     * 用户在 UI 编辑器修改目录树后,写入引擎当前树,并对所有活跃的
     * Catalog 订阅推一次 NOTIFY(对应 spec Q6 + P1-3 增量行为)。
     *
     * P1-3:对比新旧树算出 ChangeEvent,小变更走增量(`<Event>ADD/DEL/UPDATE</Event>`),
     * 大变更走全量。详见 [CatalogTreeStore.shouldUseIncremental]。
     */
    suspend fun updateCatalogTree(tree: List<com.uvp.sim.config.CatalogNode>) {
        val oldTree = _catalogTree.value
        _catalogTree.value = tree
        val events = CatalogTreeStore.diff(oldTree, tree)
        if (events.isEmpty()) return  // 无变更,不发 NOTIFY
        if (CatalogTreeStore.shouldUseIncremental(events, oldSize = oldTree.size)) {
            pushCatalogIncremental(events)
        } else {
            pushCatalogNotify()
        }
    }

    /**
     * 主动给所有活跃 Catalog 订阅推一次完整 NOTIFY(全量树)。
     * 调用时不要持 mutex,自身会 bumpNotify 维护计数。
     */
    suspend fun pushCatalogNotify() {
        val dialogs = subscriptionRegistry.dialogsByKind("Catalog")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            sendCatalogNotify(updated)
        }
    }

    /**
     * P1-3: 主动给所有活跃 Catalog 订阅推一次增量 NOTIFY,body 含
     * `<Event>ADD/DEL/UPDATE</Event>` Item 列表,WVP 只更新差异部分。
     */
    suspend fun pushCatalogIncremental(events: List<com.uvp.sim.config.CatalogChangeEvent>) {
        if (events.isEmpty()) return
        val dialogs = subscriptionRegistry.dialogsByKind("Catalog")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            sendCatalogIncrementalNotify(updated, events)
        }
    }

    private suspend fun sendCatalogIncrementalNotify(
        dialog: SubscriptionDialog,
        events: List<com.uvp.sim.config.CatalogChangeEvent>
    ) {
        catalogNotifySn++
        val xml = com.uvp.sim.gb28181.CatalogNotifyBuilder.buildIncremental(
            deviceId = config.device.deviceId,
            sn = catalogNotifySn,
            events = events
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
            _events.emit(SimEvent.NotifySent(kind = dialog.kind, sn = catalogNotifySn))
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send Catalog incremental NOTIFY: ${e::class.simpleName}: ${e.message}"))
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
            transport = config.transport.name,
            userAgent = config.userAgent
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

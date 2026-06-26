package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.coord.BroadcastCoordinatorImpl
import com.uvp.sim.domain.coord.InviteCoordinatorImpl
import com.uvp.sim.domain.coord.ManscdpRouterImpl
import com.uvp.sim.domain.coord.PlaybackCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationEvent
import com.uvp.sim.domain.coord.RegistrationState
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * Top-level orchestrator — Engine 持 5 Coord(register/broadcast/playback/invite/manscdp)+ 路由 dispatch + bridge,
 * UI 订阅 [state] / [events],动作走 [register] / [unregister]。Single-instance per session,所有副作用在 Engine 内。
 */
class SimulatorEngine(
    private val config: SimConfig,
    private val transport: SipTransport,
    scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val cameraCapture: com.uvp.sim.camera.CameraCapture? = null,
    private val audioCapture: com.uvp.sim.camera.AudioCapture? = null,
    private val rtpSenderFactory: ((host: String, port: Int, mode: com.uvp.sim.network.RtpMode) -> com.uvp.sim.network.RtpSender)? = null,
    private val recordingService: com.uvp.sim.recording.RecordingService = com.uvp.sim.recording.NoopRecordingService,
    private val playbackBuilder: PlaybackBuilder? = null,
    private val rtpReceiverFactory: ((CoroutineScope) -> com.uvp.sim.network.BroadcastRxSource)? = null,
    private val audioSinkFactory: ((Int, Int) -> com.uvp.sim.media.AudioSink)? = null,
) {
    /** Engine 自有 scope(SupervisorJob),shutdown 时 cancel 一次性收掉。 */
    private val scope: CoroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private val _state = MutableStateFlow(SipState.Disconnected)
    val state: StateFlow<SipState> = _state.asStateFlow()

    /** M5 §4.15 SIP Date 校时偏移 — 注册 200 OK 解析 Date 头后更新。 */
    private val _clockOffset = MutableStateFlow(ClockOffset.Empty)
    val clockOffset: StateFlow<ClockOffset> = _clockOffset.asStateFlow()

    /**
     * 本机 IP 的 getter delegate — 委托给 [localIpProvider]。
     *
     * T4 改造:旧版本是构造期字符串 `localIp: String = "0.0.0.0"`,新版本改为 lambda
     * provider 以支持网卡切换后动态返回新接口 IP(plan/network-selection §T4)。
     * 40+ 处使用点维持原写法 — 这个 getter 让 `localIp` 引用透明地变成"每次访问读最新值"。
     */
    private val localIp: String get() = localIpProvider()

    private val _events = MutableSharedFlow<SimEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SimEvent> = _events.asSharedFlow()

    /**
     * 5.13 / M2 §F.3 — 设备控制运行时状态.
     * UI 3D 渲染层订阅,Dispatcher 写入.
     */
    private val _deviceControlState = MutableStateFlow(DeviceControlState())
    val deviceControlState: StateFlow<DeviceControlState> = _deviceControlState.asStateFlow()

    /**
     * UI 层消费 [DeviceEffect] 后调用以清零 `pendingEffect`,防止重复触发。
     * Compose 层 `LaunchedEffect(pendingEffect)` 处理完动画/snackbar 后调本接口。
     */
    /** UI 消费 [DeviceEffect] 后清零 pendingEffect 防重复触发(Compose `LaunchedEffect`)。 */
    fun consumeEffect() {
        _deviceControlState.update { it.copy(pendingEffect = null) }
    }

    /** GlbSceneState ~10 帧/166ms 回写一次最新 PTZ pose,供预置位 SET 取真实值。 */
    fun updatePoseFromRender(pan: Float, tilt: Float, zoom: Float) {
        _deviceControlState.update { it.copy(panAngle = pan, tiltAngle = tilt, zoomLevel = zoom) }
    }

    private val mutex = Mutex()
    private var inboundJob: Job? = null

    // 全局 SIP SN 池 / dialog identity — Coord 6 个 lambda 共享读写,设备端单调 cseq
    // 详见 wiki/projects/uvp-gb28181-sim/research/2026-06-23-cseq-sn-pool-coupling.md
    private var cseq: Int = 0
    private var callId: String? = null
    private var fromTag: String? = null

    /** SIP 出站抽象(saga #5):所有 Coord 调 outbox.send,自动 emit MessageSent。 */
    private val outbox: com.uvp.sim.sip.SipOutbox =
        com.uvp.sim.sip.SipOutboxImpl(transport) { ev -> _events.emit(ev) }

    /** 注册域 — Engine 委派注册/心跳/OPTIONS/续约/重试,共享 SN 池。 */
    private val registration = RegistrationCoordinatorImpl(
        config = config, transport = transport, scope = scope, outbox = outbox,
        localIpProvider = localIpProvider, localPortProvider = localPortProvider,
        cseqProvider = { cseq }, cseqIncrementer = { cseq += 1; cseq },
        callIdProvider = { callId }, callIdSetter = { callId = it },
        fromTagProvider = { fromTag }, fromTagSetter = { fromTag = it },
    )

    private val subscriptionRegistry = SubscriptionRegistry(scope)
    private val mockGps = MockGpsSource(config.mockPosition)
    private val alarmHistoryStore = AlarmHistoryStore()
    val alarmHistory: StateFlow<List<AlarmRecord>> = alarmHistoryStore.history

    /** 目录树 — 初始从 config 取,UI 编辑器走 [updateCatalogTree] 写回。 */
    private val _catalogTree = MutableStateFlow(CatalogTreeStore.effectiveTree(config))
    val catalogTree: StateFlow<List<com.uvp.sim.config.CatalogNode>> = _catalogTree.asStateFlow()

    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> = subscriptionRegistry.subscriptions

    /** 广播域 — RX 链 / dialog state / handshake 全在本 Coord。 */
    private val broadcast: BroadcastCoordinatorImpl = BroadcastCoordinatorImpl(
        config = config, transport = transport, scope = scope, outbox = outbox,
        localIpProvider = localIpProvider, localPortProvider = localPortProvider,
        rtpReceiverFactory = rtpReceiverFactory, audioSinkFactory = audioSinkFactory,
        simEventEmit = { ev -> _events.emit(ev) },
        cseqProvider = { cseq }, cseqIncrementer = { cseq += 1; cseq },
        callIdProvider = { callId }, callIdSetter = { callId = it },
        fromTagProvider = { fromTag }, fromTagSetter = { fromTag = it },
    )

    /** 回放域 — PLAYBACK/DOWNLOAD INVITE + INFO MANSRTSP。 */
    private val playback: PlaybackCoordinatorImpl = PlaybackCoordinatorImpl(
        config = config, transport = transport, outbox = outbox, scope = scope,
        localIpProvider = localIpProvider, localPortProvider = localPortProvider,
        playbackBuilder = playbackBuilder, recordingService = recordingService,
        simEventEmit = { ev -> _events.emit(ev) },
        cseqProvider = { cseq }, cseqIncrementer = { cseq += 1; cseq },
        callIdProvider = { callId }, callIdSetter = { callId = it },
        fromTagProvider = { fromTag }, fromTagSetter = { fromTag = it },
    )

    /** 主叫直播域 — PR5 后只管直播 INVITE。 */
    private val invite: InviteCoordinatorImpl = InviteCoordinatorImpl(
        config = config, transport = transport, outbox = outbox, scope = scope,
        localIpProvider = localIpProvider, localPortProvider = localPortProvider,
        cameraCapture = cameraCapture, audioCapture = audioCapture, rtpSenderFactory = rtpSenderFactory,
        catalogTree = _catalogTree, clockOffsetProvider = { _clockOffset.value },
        mutableSipState = _state, simEventEmit = { ev -> _events.emit(ev) },
        cseqProvider = { cseq }, cseqIncrementer = { cseq += 1; cseq },
        callIdProvider = { callId }, callIdSetter = { callId = it },
        fromTagProvider = { fromTag }, fromTagSetter = { fromTag = it },
    )

    /** MANSCDP 路由域 — DeviceControl + Subscribe + 主动业务 NOTIFY。 */
    private val manscdp: ManscdpRouterImpl = ManscdpRouterImpl(
        config = config, transport = transport, scope = scope,
        localIpProvider = localIpProvider, localPortProvider = localPortProvider,
        subscriptionRegistry = subscriptionRegistry,
        catalogTree = _catalogTree,
        alarmHistoryStore = alarmHistoryStore,
        mutableDeviceControlState = _deviceControlState,
        rebootCallback = ::rebootForDeviceControl,
        requestKeyFrameCallback = { cameraCapture?.requestKeyFrame() },
        broadcastInvoker = broadcast,
        recordingService = recordingService,
        mockGps = mockGps,
        clockOffsetProvider = { _clockOffset.value },
        stateRegisteredOrInCall = { _state.value == SipState.Registered || _state.value == SipState.InCall },
        broadcastBusy = { broadcast.current.value != null },
        simEventEmit = { ev -> _events.emit(ev) },
        cseqProvider = { cseq }, cseqIncrementer = { cseq += 1; cseq },
        callIdProvider = { callId }, callIdSetter = { callId = it },
        fromTagProvider = { fromTag }, fromTagSetter = { fromTag = it },
    )

    /** 当前推流通道的显示名 — 委派给 InviteCoordinator(PR4 T4.3)。 */
    val currentChannelName: StateFlow<String> get() = invite.currentChannelName

    /** Engine 不在 InCall 时,把 registration.state 单向直写到 _state(InCall 由业务路径维护)。 */
    private val registrationStateBridge: Job = scope.launch {
        registration.state.collect { regState ->
            if (_state.value != SipState.InCall) _state.value = mapRegistrationState(regState)
        }
    }
    private val registrationEventBridge: Job = scope.launch {
        registration.events.collect { ev ->
            val sim: SimEvent? = when (ev) {
                is RegistrationEvent.Registered, is RegistrationEvent.Renewed ->
                    SimEvent.RegistrationSucceeded(config.expiresSeconds)
                is RegistrationEvent.AuthChallenged -> SimEvent.RegistrationChallenged(ev.realm)
                is RegistrationEvent.Unauthorized -> SimEvent.RegistrationFailed(ev.reason)
                is RegistrationEvent.NetworkSwitchedReregister -> null
                is RegistrationEvent.AutoReregisterTriggered -> {
                    invite.stopStream("auto re-register triggered")
                    SimEvent.AutoReregisterTriggered(ev.reason)
                }
            }
            if (sim != null) _events.emit(sim)
        }
    }
    private val registrationClockBridge: Job = scope.launch {
        registration.clockOffset.collect { off -> _clockOffset.value = off }
    }

    /** Initiate registration. Returns immediately; observe [state] for completion. */
    suspend fun register() {
        startInboundIfNeeded()
        _events.emit(SimEvent.RegistrationStarted("${config.server.ip}:${config.server.port}"))
        registration.register()
        syncStateFromRegistration()
    }

    /** Cancel an in-flight REGISTER (before any response). Used by UI cancel. */
    suspend fun cancelRegister() {
        registration.cancelRegister()
        syncStateFromRegistration()
    }

    suspend fun unregister() {
        invite.stopStream("user unregister")
        SystemLogger.emit(LogLevel.Info, LogTag.Lifecycle, "用户注销 → 发送 Unregister")
        subscriptionRegistry.cancelAll()
        registration.unregister()
        syncStateFromRegistration()
    }

    /**
     * NetworkController 状态变化驱动重注册,Contact/Via 头刷到新接口 IP。
     * 软切换:in-flight INVITE 不主动 BYE(java.nio 不迁移,等平台 BYE)。
     */
    suspend fun handleNetworkChange(newState: com.uvp.sim.network.NetworkState) {
        when (newState) {
            is com.uvp.sim.network.NetworkState.Bound -> {
                _events.emit(SimEvent.NetworkBound(newState.preference.name, newState.interfaceName, newState.localIp))
                SystemLogger.emit(LogLevel.Info, LogTag.Network,
                    "网络已切到 ${newState.preference.name} 接口 ${newState.interfaceName} IP=${newState.localIp},触发重注册")
                triggerReregisterIfActive()
            }
            com.uvp.sim.network.NetworkState.Auto -> {
                _events.emit(SimEvent.NetworkAuto)
                SystemLogger.emit(LogLevel.Info, LogTag.Network, "网络偏好 → 自动,触发重注册以刷新 Contact 头")
                triggerReregisterIfActive()
            }
            is com.uvp.sim.network.NetworkState.Unavailable -> {
                _events.emit(SimEvent.NetworkUnavailable(newState.reason))
                SystemLogger.emit(LogLevel.Warning, LogTag.Network, "网络不可用: ${newState.reason}")
            }
            is com.uvp.sim.network.NetworkState.Switching -> Unit
        }
    }

    /** 在线时才驱动 unregister → register;Disconnected/Failed 不替老板决定。 */
    private suspend fun triggerReregisterIfActive() {
        val cur = _state.value
        if (cur != SipState.Registered && cur != SipState.InCall && cur != SipState.Registering) return
        runCatching { unregister() }.onFailure {
            SystemLogger.emit(LogLevel.Warning, LogTag.Network, "重注册 unregister 抛错: ${it::class.simpleName}: ${it.message}")
        }
        runCatching { register() }.onFailure {
            SystemLogger.emit(LogLevel.Error, LogTag.Network, "重注册 register 抛错: ${it::class.simpleName}: ${it.message}")
        }
    }

    /** Transport 自身不在这关 — 由 caller 负责。 */
    suspend fun shutdown() {
        invite.shutdown(); playback.shutdown(); broadcast.shutdown()
        mutex.withLock {
            subscriptionRegistry.cancelAll()
            inboundJob?.cancel(); inboundJob = null
            _state.value = SipState.Disconnected
        }
        registration.shutdown()
        registrationStateBridge.cancel(); registrationEventBridge.cancel(); registrationClockBridge.cancel()
        scope.cancel()
    }

    // ---- 主动业务 + 配置 / 状态委派 — 全部委派给对应 Coord ----

    suspend fun reportSnapshot() = manscdp.reportSnapshot()
    fun attachSnapshotPipeline(
        capture: com.uvp.sim.snapshot.SnapshotCapture,
        cache: com.uvp.sim.snapshot.JpegLocalCache,
        httpClient: io.ktor.client.HttpClient,
    ) = manscdp.attachSnapshotPipeline(capture, cache, httpClient)

    suspend fun reportAlarm(payload: AlarmPayload) = manscdp.reportAlarm(payload)
    suspend fun localResetAlarm() = manscdp.localResetAlarm()
    suspend fun triggerMediaStatusAbnormal(notifyType: Int) = manscdp.triggerMediaStatusAbnormal(notifyType)

    suspend fun updateCatalogTree(tree: List<com.uvp.sim.config.CatalogNode>) = manscdp.updateCatalogTree(tree)
    suspend fun pushCatalogNotify() = manscdp.pushCatalogNotify()
    suspend fun pushCatalogIncremental(events: List<com.uvp.sim.config.CatalogChangeEvent>) =
        manscdp.pushCatalogIncremental(events)
    suspend fun toggleChannelStatus(channelId: String, online: Boolean) =
        manscdp.toggleChannelStatus(channelId, online)

    /** 5.5 device-initiated BYE — Invite 域。 */
    suspend fun stopStream(reason: String = "user stop") = invite.stopStream(reason)

    // Broadcast 域公开 API — 全部委派 BroadcastCoordinator
    val currentBroadcast: StateFlow<BroadcastDialog?> get() = broadcast.current
    val broadcastSpeakerOn: StateFlow<Boolean> get() = broadcast.speakerOn
    fun setBroadcastSpeaker(on: Boolean) = broadcast.setSpeaker(on)
    suspend fun stopBroadcast(reason: BroadcastEndReason = BroadcastEndReason.Local) = broadcast.stop(reason)

    /** test-only hooks — 绕节流读 RX 计数 / 直注 RTP 包,避免真 socket。 */
    internal fun rxPacketCountForTest(): Long = broadcast.debugSnapshot().rxPacketCount
    internal fun decodeErrorCountForTest(): Long = broadcast.debugSnapshot().decodeErrorCount
    internal fun isRxActive(): Boolean = broadcast.debugSnapshot().rxActive
    internal suspend fun handleRxPacket(rtp: com.uvp.sim.network.RtpPacket) = broadcast.handleRxPacket(rtp)


    private fun startInboundIfNeeded() {
        if (inboundJob != null) return
        inboundJob = scope.launch {
            try {
                transport.incoming.collect { msg ->
                    _events.emit(SimEvent.MessageReceived(msg))
                    try { handleIncoming(msg) } catch (e: Throwable) {
                        _events.emit(SimEvent.TransportError("handleIncoming: ${e::class.simpleName}: ${e.message}"))
                    }
                }
            } catch (e: Throwable) {
                _events.emit(SimEvent.TransportError("inbound: ${e::class.simpleName}: ${e.message}"))
            }
        }
    }

    private suspend fun handleIncoming(msg: SipMessage) = when (msg) {
        is SipResponse -> handleResponse(msg)
        is SipRequest -> handleRequest(msg)
    }

    private suspend fun handleResponse(resp: SipResponse) {
        val cseqHeader = resp.cseqRaw() ?: return
        val cseqMethod = cseqHeader.split(" ").getOrNull(1)?.let { SipMethod.fromString(it) } ?: return
        when (cseqMethod) {
            SipMethod.REGISTER -> { registration.onIncoming(resp); syncStateFromRegistration() }
            SipMethod.INVITE -> broadcast.onIncoming(resp)
            SipMethod.MESSAGE -> if (resp.statusCode in 200..299) {
                registration.onIncoming(resp)
                _events.emit(SimEvent.HeartbeatAcknowledged(0))
            }
            else -> Unit
        }
    }

    /** Coord 改完状态后立即同步,避免 bridge 协程时序滞后让后续消息读到陈旧状态。 */
    private fun syncStateFromRegistration() {
        if (_state.value == SipState.InCall) return
        _state.value = mapRegistrationState(registration.state.value)
    }

    private fun mapRegistrationState(reg: RegistrationState): SipState = when (reg) {
        RegistrationState.Disconnected -> SipState.Disconnected
        RegistrationState.Registering, RegistrationState.RetryBackoff -> SipState.Registering
        RegistrationState.Registered -> SipState.Registered
        RegistrationState.Failed -> SipState.Failed
    }

    private suspend fun handleRequest(req: SipRequest) {
        val skip = com.uvp.sim.domain.coord.RoutingResult.Skip
        when (req.method) {
            SipMethod.INVITE -> if (invite.onIncoming(req) == skip) playback.onIncoming(req)
            SipMethod.ACK, SipMethod.CANCEL -> invite.onIncoming(req)
            SipMethod.BYE -> {
                if (broadcast.onIncoming(req) == skip &&
                    invite.onIncoming(req) == skip) playback.onIncoming(req)
            }
            SipMethod.INFO -> if (playback.onIncoming(req) == skip) manscdp.onIncoming(req)
            SipMethod.MESSAGE, SipMethod.SUBSCRIBE -> manscdp.onIncoming(req)
            SipMethod.OPTIONS -> registration.onIncoming(req)
            else -> Unit
        }
    }

    /** DeviceControl TeleBoot 回调(followup A 注入 Manscdp)。 */
    private suspend fun rebootForDeviceControl() {
        SystemLogger.emit(LogLevel.Info, LogTag.Lifecycle, "TeleBoot → 重新注册")
        try { unregister() } catch (_: Throwable) { /* 平台可能已不可达 */ }
        delay(1_000L)
        register()
    }
}

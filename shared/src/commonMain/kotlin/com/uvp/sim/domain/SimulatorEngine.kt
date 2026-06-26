package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.coord.BroadcastCoordinatorImpl
import com.uvp.sim.domain.coord.BroadcastInvoker
import com.uvp.sim.domain.coord.InviteCoordinatorImpl
import com.uvp.sim.domain.coord.ManscdpRouterImpl
import com.uvp.sim.domain.coord.PlaybackCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationEvent
import com.uvp.sim.domain.coord.RegistrationState
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.MobilePositionNotify
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import kotlinx.datetime.Instant
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
    scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
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
    private val playbackBuilder: PlaybackBuilder? = null,
    /**
     * M3 语音广播 RX 接收器工厂。null 时用默认 expect class(Android = java.net UDP)。
     * 测试注入 fake 以避免真实 socket。
     */
    private val rtpReceiverFactory: ((CoroutineScope) -> com.uvp.sim.network.BroadcastRxSource)? = null,
    /**
     * M3 语音广播扬声器工厂。null 时用默认(Android = AudioTrack)。测试注入 fake。
     */
    private val audioSinkFactory: ((Int, Int) -> com.uvp.sim.media.AudioSink)? = null
) {
    /**
     * Engine 自有的协程 scope(PR2 T2.3b)。继承外部传入 [scope] 的 dispatcher / job 树,
     * 但加 [SupervisorJob] 让 Coordinator 内部协程的失败不传染到外部。所有内部 launch /
     * stateIn / collect 都走这个 scope,[shutdown] 时 cancel 一次性收掉。
     */
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
    fun consumeEffect() {
        _deviceControlState.update { it.copy(pendingEffect = null) }
    }

    /**
     * 渲染层节流回写当前 PTZ 姿态.
     *
     * 背景:`DeviceControlState.panAngle/tiltAngle/zoomLevel` 设计上由 UI 渲染层
     * 维护(每帧基于 panSpeed 等积分)。但 `GlbSceneState`(Filament)以前从未把
     * 渲染端的 panAngle 推回 state,导致 `handlePtzPreset SET` 取的永远是 0/0/1
     * → 预置位调用回原点而不是真实位置。
     *
     * 本接口由 `GlbSceneState` 每 ~10 帧(166ms)调用一次,推渲染端最新 pose 进 state。
     */
    fun updatePoseFromRender(pan: Float, tilt: Float, zoom: Float) {
        _deviceControlState.update {
            it.copy(panAngle = pan, tiltAngle = tilt, zoomLevel = zoom)
        }
    }

    private val mutex = Mutex()
    private var inboundJob: Job? = null

    // 全局 SIP SN 池 / dialog identity — Coord 6 个 lambda 共享读写,设备端单调 cseq
    // 详见 wiki/projects/uvp-gb28181-sim/research/2026-06-23-cseq-sn-pool-coupling.md
    private var cseq: Int = 0
    private var callId: String? = null
    private var fromTag: String? = null

    /** 注册域 — Engine 委派注册/心跳/OPTIONS/续约/重试,共享 SN 池。 */
    private val registration = RegistrationCoordinatorImpl(
        config = config, transport = transport, scope = scope,
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
        config = config, transport = transport, scope = scope,
        localIpProvider = localIpProvider, localPortProvider = localPortProvider,
        rtpReceiverFactory = rtpReceiverFactory, audioSinkFactory = audioSinkFactory,
        simEventEmit = { ev -> _events.emit(ev) },
        cseqProvider = { cseq }, cseqIncrementer = { cseq += 1; cseq },
        callIdProvider = { callId }, callIdSetter = { callId = it },
        fromTagProvider = { fromTag }, fromTagSetter = { fromTag = it },
    )

    /** 回放域 — PLAYBACK/DOWNLOAD INVITE + INFO MANSRTSP。 */
    private val playback: PlaybackCoordinatorImpl = PlaybackCoordinatorImpl(
        config = config, transport = transport, scope = scope,
        localIpProvider = localIpProvider, localPortProvider = localPortProvider,
        playbackBuilder = playbackBuilder, recordingService = recordingService,
        simEventEmit = { ev -> _events.emit(ev) },
        cseqProvider = { cseq }, cseqIncrementer = { cseq += 1; cseq },
        callIdProvider = { callId }, callIdSetter = { callId = it },
        fromTagProvider = { fromTag }, fromTagSetter = { fromTag = it },
    )

    /** 主叫直播域 — PR5 后只管直播 INVITE。 */
    private val invite: InviteCoordinatorImpl = InviteCoordinatorImpl(
        config = config, transport = transport, scope = scope,
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
        startUpgradeCallback = ::startUpgradeForDeviceControl,
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

    /**
     * 注册域桥接 job(PR2 T2.3b):把 Coordinator 自管的 [registration.state] 单向直写到
     * Engine 的 [_state],并把 [registration.events] 翻译成 [SimEvent] 转发给 UI。
     * Engine 仍然在某些路径直接写 [_state](INVITE / BYE 等),完整 derived state 留 PR4/PR5。
     *
     * 单向语义:Coord 是注册栈的"真相源",Engine 不反向写 registration.state。
     * shutdown 时 cancel 一次性收掉。
     */
    private val registrationStateBridge: Job = scope.launch {
        registration.state.collect { regState ->
            val mapped = when (regState) {
                RegistrationState.Disconnected -> SipState.Disconnected
                RegistrationState.Registering -> SipState.Registering
                RegistrationState.Registered -> SipState.Registered
                RegistrationState.RetryBackoff -> SipState.Registering
                RegistrationState.Failed -> SipState.Failed
            }
            // 单向直写:仅当 Engine 当前不在 InCall 时同步,InCall 由业务路径维护
            if (_state.value != SipState.InCall) {
                _state.value = mapped
            }
        }
    }
    private val registrationEventBridge: Job = scope.launch {
        registration.events.collect { ev ->
            val sim = when (ev) {
                is RegistrationEvent.Registered ->
                    SimEvent.RegistrationSucceeded(config.expiresSeconds)
                is RegistrationEvent.Renewed ->
                    SimEvent.RegistrationSucceeded(config.expiresSeconds)
                is RegistrationEvent.AuthChallenged ->
                    SimEvent.RegistrationChallenged(ev.realm)
                is RegistrationEvent.Unauthorized ->
                    SimEvent.RegistrationFailed(ev.reason)
                is RegistrationEvent.NetworkSwitchedReregister -> null
                is RegistrationEvent.AutoReregisterTriggered -> {
                    // PR4 T4.3:活跃流停止委派给 InviteCoordinator
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

    /** Send Unregister and tear down. */
    suspend fun unregister() {
        // PR4 T4.3:活跃流停止委派给 InviteCoordinator(决策 2 选 A)
        invite.stopStream("user unregister")
        SystemLogger.emit(
            LogLevel.Info, LogTag.Lifecycle,
            "用户注销 → 发送 Unregister"
        )
        subscriptionRegistry.cancelAll()
        registration.unregister()
        syncStateFromRegistration()
    }

    /**
     * NetworkController 状态变化驱动重注册,Contact/Via 头刷到新接口 IP。
     * 软切换:in-flight INVITE 不主动 BYE(java.nio 不迁移,等平台 BYE);
     * 老 IP 的 Expires=0 unregister 可能发不出去,服务端绑定自然过期。
     */
    suspend fun handleNetworkChange(newState: com.uvp.sim.network.NetworkState) {
        when (newState) {
            is com.uvp.sim.network.NetworkState.Bound -> {
                _events.emit(
                    SimEvent.NetworkBound(
                        preference = newState.preference.name,
                        interfaceName = newState.interfaceName,
                        localIp = newState.localIp,
                    )
                )
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Network,
                    "网络已切到 ${newState.preference.name} 接口 ${newState.interfaceName} IP=${newState.localIp},触发重注册"
                )
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
        val current = _state.value
        if (current != SipState.Registered && current != SipState.InCall && current != SipState.Registering) return
        runCatching { unregister() }.onFailure {
            SystemLogger.emit(LogLevel.Warning, LogTag.Network, "重注册 unregister 抛错: ${it::class.simpleName}: ${it.message}")
        }
        runCatching { register() }.onFailure {
            SystemLogger.emit(LogLevel.Error, LogTag.Network, "重注册 register 抛错: ${it::class.simpleName}: ${it.message}")
        }
    }

    /** Stop background work. The transport itself is not closed (caller's responsibility). */
    suspend fun shutdown() {
        // PR5 T5.4:活跃域全部归 Coordinator,Engine 顺序调 shutdown
        invite.shutdown()
        playback.shutdown()
        broadcast.shutdown()
        mutex.withLock {
            subscriptionRegistry.cancelAll()
            inboundJob?.cancel()
            inboundJob = null
            _state.value = SipState.Disconnected
        }
        registration.shutdown()
        registrationStateBridge.cancel()
        registrationEventBridge.cancel()
        registrationClockBridge.cancel()
        scope.cancel()
    }

    /**
     * T15 — Snapshot upload (主动业务: 抓拍上报). PR3 T3.4 委派给 ManscdpRouter.
     */
    suspend fun reportSnapshot() = manscdp.reportSnapshot()

    fun attachSnapshotPipeline(
        capture: com.uvp.sim.snapshot.SnapshotCapture,
        cache: com.uvp.sim.snapshot.JpegLocalCache,
        httpClient: io.ktor.client.HttpClient,
    ) {
        manscdp.attachSnapshotPipeline(capture, cache, httpClient)
    }

    /** M2 Alarm — 主动发起报警(PR3 T3.4 委派). */
    suspend fun reportAlarm(payload: AlarmPayload) = manscdp.reportAlarm(payload)

    /** M2 Alarm — 用户本地复位(PR3 T3.4 委派). */
    suspend fun localResetAlarm() = manscdp.localResetAlarm()

    /** M5 batch1 §C2 — 主动通知平台异常媒体状态(PR3 T3.4 委派). */
    suspend fun triggerMediaStatusAbnormal(notifyType: Int) = manscdp.triggerMediaStatusAbnormal(notifyType)

    /** Catalog 树更新 + 增量/全量 NOTIFY fan-out(PR3 T3.4 委派). */
    suspend fun updateCatalogTree(tree: List<com.uvp.sim.config.CatalogNode>) =
        manscdp.updateCatalogTree(tree)

    /** Catalog 全量 NOTIFY fan-out(PR3 T3.4 委派). */
    suspend fun pushCatalogNotify() = manscdp.pushCatalogNotify()

    /** Catalog 增量 NOTIFY fan-out(PR3 T3.4 委派). */
    suspend fun pushCatalogIncremental(events: List<com.uvp.sim.config.CatalogChangeEvent>) =
        manscdp.pushCatalogIncremental(events)

    /** 通道在线状态切换 + 简化 NOTIFY fan-out(PR3 T3.4 委派). */
    suspend fun toggleChannelStatus(channelId: String, online: Boolean) =
        manscdp.toggleChannelStatus(channelId, online)

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    /** 5.5 device-initiated BYE — PR4 T4.3 委派给 InviteCoordinator(决策 2 选 A)。 */
    suspend fun stopStream(reason: String = "user stop") = invite.stopStream(reason)

    // ---- PR5 T5.4:Broadcast public API 全部委派给 BroadcastCoordinator ----

    /** 当前广播 dialog state — 委派给 BroadcastCoordinator(PR5)。 */
    val currentBroadcast: StateFlow<BroadcastDialog?> get() = broadcast.current

    /** 扬声器开关 — 委派给 BroadcastCoordinator(PR5)。 */
    val broadcastSpeakerOn: StateFlow<Boolean> get() = broadcast.speakerOn

    /** 切换语音对讲扬声器(静音不影响 RTP 接收 / dialog,只 gate 写 AudioTrack)。 */
    fun setBroadcastSpeaker(on: Boolean) = broadcast.setSpeaker(on)

    /** 用户主动停止语音广播(UI ✕)。 */
    suspend fun stopBroadcast(reason: BroadcastEndReason = BroadcastEndReason.Local) =
        broadcast.stop(reason)

    /** 测试可见:RX 热计数(绕过每秒节流直接读)。 */
    internal fun rxPacketCountForTest(): Long = broadcast.debugSnapshot().rxPacketCount
    internal fun decodeErrorCountForTest(): Long = broadcast.debugSnapshot().decodeErrorCount
    internal fun isRxActive(): Boolean = broadcast.debugSnapshot().rxActive

    /** 测试可见:test 直注 RTP 包(绕过真 socket)。 */
    internal suspend fun handleRxPacket(rtp: com.uvp.sim.network.RtpPacket) =
        broadcast.handleRxPacket(rtp)


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
            SipMethod.REGISTER -> {
                registration.onIncoming(resp)
                syncStateFromRegistration()
            }
            // PR5 T5.4:INVITE 响应(主叫 broadcast)归 BroadcastCoordinator
            SipMethod.INVITE -> broadcast.onIncoming(resp)
            SipMethod.MESSAGE -> {
                if (resp.statusCode in 200..299) {
                    registration.onIncoming(resp)
                    _events.emit(SimEvent.HeartbeatAcknowledged(0))
                }
            }
            else -> Unit
        }
    }

    /**
     * 把 [registration] 状态同步映射回 Engine 的 [_state]。InCall 期间不回写,
     * 保留 Engine 持有的业务状态。仅在路由层调用 — Coord 改完状态后立即拉回,
     * 避免 bridge 协程时序滞后导致后续消息(如 INVITE)读到陈旧状态。
     */
    private fun syncStateFromRegistration() {
        if (_state.value == SipState.InCall) return
        _state.value = when (registration.state.value) {
            RegistrationState.Disconnected -> SipState.Disconnected
            RegistrationState.Registering -> SipState.Registering
            RegistrationState.Registered -> SipState.Registered
            RegistrationState.RetryBackoff -> SipState.Registering
            RegistrationState.Failed -> SipState.Failed
        }
    }

    private suspend fun handleRequest(req: SipRequest) {
        when (req.method) {
            // PR4 T4.3:INVITE / ACK / CANCEL 委派 InviteCoordinator
            // PR5 T5.4:INVITE 先试 invite(直播),Skip 时给 playback
            SipMethod.INVITE -> {
                if (invite.onIncoming(req) == com.uvp.sim.domain.coord.RoutingResult.Skip) {
                    playback.onIncoming(req)
                }
            }
            SipMethod.ACK -> invite.onIncoming(req)
            SipMethod.CANCEL -> invite.onIncoming(req)
            // BYE:顺序路由 broadcast → invite → playback
            SipMethod.BYE -> {
                var result = broadcast.onIncoming(req)
                if (result == com.uvp.sim.domain.coord.RoutingResult.Skip) result = invite.onIncoming(req)
                if (result == com.uvp.sim.domain.coord.RoutingResult.Skip) playback.onIncoming(req)
            }
            // INFO:先 playback(MANSRTSP),Skip 给 manscdp
            SipMethod.INFO -> {
                if (playback.onIncoming(req) == com.uvp.sim.domain.coord.RoutingResult.Skip) {
                    manscdp.onIncoming(req)
                }
            }
            SipMethod.MESSAGE -> manscdp.onIncoming(req)
            SipMethod.SUBSCRIBE -> manscdp.onIncoming(req)
            SipMethod.OPTIONS -> registration.onIncoming(req)
            else -> Unit
        }
    }

    // handleInfo / sendSimpleResponse / handleAck / handleCancel 全部迁到 InviteCoordinator(PR4 T4.3)

    /** 把已构造的 Broadcast Response MANSCDP body 包成 MESSAGE 发给平台(第二条,200 OK 之外)。 */
    /**
     * GB-2022 §9.13 设备升级假进度 — 5s 内每秒推一次 DeviceUpgradeResult NOTIFY (0/30/60/100).
     * 完成时推 result=1 + percent=100,同步写 _deviceControlState.upgradeProgress.
     */
    private suspend fun runUpgradeProgressFlow(sessionId: String, firmware: String) {
        try {
            val steps = listOf(0, 30, 60, 100)
            for ((i, percent) in steps.withIndex()) {
                _deviceControlState.update {
                    it.copy(
                        upgradeProgress = UpgradeProgress(
                            sessionId = sessionId,
                            firmware = firmware,
                            percent = percent,
                            result = if (percent < 100) UpgradeResult.InProgress else UpgradeResult.Success,
                        )
                    )
                }
                sendDeviceUpgradeResultNotify(
                    sessionId = sessionId,
                    firmware = firmware,
                    percent = percent,
                    result = if (percent < 100)
                        com.uvp.sim.sip.DeviceUpgradeResultNotify.RESULT_IN_PROGRESS
                    else
                        com.uvp.sim.sip.DeviceUpgradeResultNotify.RESULT_SUCCESS,
                )
                if (i < steps.lastIndex) delay(1_500L)  // 步间隔
            }
            // 完成后保留 5s 让 UI 显示成功状态,然后清零
            delay(5_000L)
            _deviceControlState.update { it.copy(upgradeProgress = null) }
        } catch (e: Throwable) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "DeviceUpgrade 假进度异常: ${e.message}"
            )
        }
    }

    private suspend fun sendDeviceUpgradeResultNotify(
        sessionId: String,
        firmware: String,
        percent: Int,
        result: Int,
    ) {
        try {
            cseq += 1
            val branch = com.uvp.sim.sip.SipBuilders.randomBranch()
            val callIdNow = com.uvp.sim.sip.SipBuilders.randomCallId(localIp)
            val fromTagNow = com.uvp.sim.sip.SipBuilders.randomTag()
            val msg = com.uvp.sim.sip.DeviceUpgradeResultNotify.build(
                config = config,
                cseq = cseq,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                sn = (cseq and 0xFFFF),
                sessionId = sessionId,
                firmware = firmware,
                result = result,
                percent = percent,
            )
            transport.send(msg)
            _events.emit(SimEvent.MessageSent(msg))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "DeviceUpgradeResult NOTIFY → 进度 $percent% result=$result session=$sessionId"
            )
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("send DeviceUpgradeResult NOTIFY: ${e.message}"))
        }
    }

    /** DeviceControl TeleBoot 回调(followup A 注入 Manscdp)。 */
    private suspend fun rebootForDeviceControl() {
        SystemLogger.emit(LogLevel.Info, LogTag.Lifecycle, "TeleBoot → 重新注册")
        try {
            unregister()
        } catch (_: Throwable) { /* 平台可能已不可达 */ }
        delay(1_000L)
        register()
    }

    /** DeviceControl DeviceUpgrade 回调(followup A 注入 Manscdp;E 动作再迁内部)。 */
    private fun startUpgradeForDeviceControl(sessionId: String, firmware: String, fileUrl: String) {
        SystemLogger.emit(
            LogLevel.Info, LogTag.Lifecycle,
            "DeviceUpgrade → 启动假进度 SessionID=$sessionId Firmware=$firmware URL=$fileUrl"
        )
        scope.launch { runUpgradeProgressFlow(sessionId, firmware) }
    }


    companion object {
        const val REGISTER_TIMEOUT_MS: Long = 8_000L
        const val MEDIA_STATS_INTERVAL_MS: Long = 30_000L
        const val MAX_REGISTER_RETRIES: Int = 3
        const val INITIAL_RETRY_DELAY_MS: Long = 2_000L
        /** RFC 3261 § 17.2.1 Timer H = 64*T1 = 32s for ACK reception window. */
        const val ACK_TIMEOUT_MS: Long = 32_000L
        /** 语音广播接收统计节流间隔。 */
        const val BROADCAST_STATS_INTERVAL_MS: Long = 1_000L

        /** M5 batch3 §9.9 RTCP SR 周期(RFC3550 § 6.2 推荐 5%-10% 带宽,5s 是经典值)。 */
        const val RTCP_SR_INTERVAL_MS: Long = 5_000L

        /**
         * RFC 3261 §11 OPTIONS Allow 头集 — sim 真实支持(可被路由处理)的方法.
         * REGISTER 设备只发不收,故不在 Allow.顺序按 [SipMethod] 枚举声明顺序,跟代码自洽.
         */
        val ALLOWED_OPTIONS_METHODS: List<SipMethod> = listOf(
            SipMethod.INVITE, SipMethod.ACK, SipMethod.BYE, SipMethod.MESSAGE,
            SipMethod.SUBSCRIBE, SipMethod.NOTIFY, SipMethod.CANCEL,
            SipMethod.INFO, SipMethod.OPTIONS
        )
    }
}

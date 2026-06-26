package com.uvp.sim.app

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmHistoryStore
import com.uvp.sim.domain.AlarmRecord
import com.uvp.sim.domain.BroadcastDialog
import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.EngineCoordinators
import com.uvp.sim.domain.EngineHolders
import com.uvp.sim.domain.MockGpsSource
import com.uvp.sim.domain.RegisterPoolLambdas
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SimulatorEngine
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.domain.SubscriptionSnapshot
import com.uvp.sim.domain.newDefaultIdentityService
import com.uvp.sim.domain.registerPoolLambdasFrom
import com.uvp.sim.domain.coord.BroadcastCoordinatorImpl
import com.uvp.sim.domain.coord.InviteCoordinatorImpl
import com.uvp.sim.domain.coord.ManscdpRouterImpl
import com.uvp.sim.domain.coord.PlaybackCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationCoordinatorImpl
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.network.NetworkState
import com.uvp.sim.network.RemoteEndpoint
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.SipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.network.UdpSipTransport
import com.uvp.sim.sip.SipOutboxImpl
import com.uvp.sim.sip.SipState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AppEngine — 装配根(P1.5 真下沉)。
 *
 * commonMain 持 Engine 实例 + transport 装配 + 5 Coord buildCoordinators + 8 holder buildHolders。
 * Engine 退化为「5 Coord 引用 + 路由 + bridge」,装配链全在 AppEngine。
 * Android/iOS ViewModel 退化成薄转发,不再持装配逻辑。
 *
 * 公开 API 签名保持(避让轨 3 PR-B AppActions 拆切口)。
 */
class AppEngine(
    private val resources: AndroidResources,
    initialConfig: SimConfig,
    parentScope: CoroutineScope,
) {
    private val engineScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    private val _config = MutableStateFlow(initialConfig)
    val config: StateFlow<SimConfig> = _config.asStateFlow()

    /** 配置持久化层(委派 ConfigStore)。public 让 ViewModel 直接 loadOnce on cold start。 */
    val configStore: ConfigStore get() = resources.configStore

    private var transport: SipTransport? = null
    private var engine: SimulatorEngine? = null
    private var snapshotHttp: HttpClient? = null

    /** holders 在 AppEngine own,Engine 通过构造接引用 — 公开 StateFlow 直读 holder,免去 9 个 collect bridge。 */
    private val holders: EngineHolders = EngineHolders(
        state = MutableStateFlow(SipState.Disconnected),
        events = MutableSharedFlow(extraBufferCapacity = 64),
        deviceControlState = MutableStateFlow(DeviceControlState()),
        catalogTree = MutableStateFlow(CatalogTreeStore.effectiveTree(initialConfig)),
        clockOffset = MutableStateFlow(ClockOffset.Empty),
        alarmHistoryStore = AlarmHistoryStore(),
        subscriptionRegistry = SubscriptionRegistry(engineScope),
        mockGps = MockGpsSource(initialConfig.mockPosition),
        identityService = newDefaultIdentityService(localIpProvider = resources.localIpProvider),
    )

    val state: StateFlow<SipState> = holders.state.asStateFlow()
    val events: SharedFlow<SimEvent> = holders.events.asSharedFlow()
    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> = holders.subscriptionRegistry.subscriptions
    val deviceControlState: StateFlow<DeviceControlState> = holders.deviceControlState.asStateFlow()
    val catalogTree: StateFlow<List<CatalogNode>> = holders.catalogTree.asStateFlow()
    val alarmHistory: StateFlow<List<AlarmRecord>> = holders.alarmHistoryStore.history
    val clockOffset: StateFlow<ClockOffset> = holders.clockOffset.asStateFlow()

    private val _currentChannelName = MutableStateFlow(initialConfig.device.videoChannelName)
    val currentChannelName: StateFlow<String> = _currentChannelName.asStateFlow()

    private val _currentBroadcast = MutableStateFlow<BroadcastDialog?>(null)
    val currentBroadcast: StateFlow<BroadcastDialog?> = _currentBroadcast.asStateFlow()

    private val _broadcastSpeakerOn = MutableStateFlow(true)
    val broadcastSpeakerOn: StateFlow<Boolean> = _broadcastSpeakerOn.asStateFlow()

    /**
     * 连接平台。装配链:transport → buildHolders(已 lazy)→ buildCoordinators → Engine →
     * Coord<->Engine 反向 ref → snapshot pipeline → bridge(currentChannelName/currentBroadcast/broadcastSpeakerOn 3 个 invite/broadcast 专属)→ register。
     */
    suspend fun connect() {
        val existing = engine
        if (existing != null) {
            when (holders.state.value) {
                SipState.Registering, SipState.Registered, SipState.InCall -> return
                SipState.Disconnected, SipState.Failed -> {
                    try {
                        transport?.connect()
                        existing.register()
                    } catch (e: Throwable) {
                        holders.events.emit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("register retry", e)))
                    }
                    return
                }
            }
        }

        val cfg = _config.value
        val tx: SipTransport = when (cfg.transport) {
            TransportType.TCP -> com.uvp.sim.network.TcpSipTransport(
                remote = RemoteEndpoint(cfg.server.ip, cfg.server.port, TransportType.TCP),
                parentScope = engineScope,
            )
            TransportType.UDP -> UdpSipTransport(
                remote = RemoteEndpoint(cfg.server.ip, cfg.server.port, TransportType.UDP),
                parentScope = engineScope,
            )
        }
        transport = tx

        val outbox = SipOutboxImpl(tx) { ev -> holders.events.emit(ev) }
        var engineRef: SimulatorEngine? = null
        val coords = buildCoordinators(cfg, tx, outbox) { engineRef }
        val eng = SimulatorEngine(cfg, tx, engineScope, resources, coords, holders)
        engine = eng
        engineRef = eng

        val capture = resources.snapshotCapture
        val cache = resources.snapshotCache
        val httpEngine = resources.httpEngineFactory?.invoke()
        if (capture != null && cache != null && httpEngine != null) {
            val http = HttpClient(httpEngine)
            snapshotHttp = http
            eng.attachSnapshotPipeline(capture, cache, http)
            engineScope.launch { runCatching { cache.gc() } }
        }

        // 3 个 invite/broadcast 专属 StateFlow 仍 collect 桥接(它们的 source-of-truth 在 Coord 内部 — 不在 holders)
        engineScope.launch { eng.currentChannelName.collect { _currentChannelName.value = it } }
        engineScope.launch { eng.currentBroadcast.collect { _currentBroadcast.value = it } }
        engineScope.launch { eng.broadcastSpeakerOn.collect { _broadcastSpeakerOn.value = it } }

        try {
            tx.connect()
            eng.register()
        } catch (e: Throwable) {
            holders.events.emit(SimEvent.TransportError(com.uvp.sim.domain.mapToUserError("connect", e)))
        }
    }

    private fun buildCoordinators(
        cfg: SimConfig,
        tx: SipTransport,
        outbox: com.uvp.sim.sip.SipOutbox,
        engineRefProvider: () -> SimulatorEngine?,
    ): EngineCoordinators {
        val localPortProvider: () -> Int = { tx.localPort.takeIf { it > 0 } ?: 5060 }
        val rtpFactory: ((String, Int, RtpMode) -> com.uvp.sim.network.RtpSender)? =
            resources.rtpSenderFactory?.let { f -> { host, port, mode -> f(host, port, engineScope, mode) } }
        val playbackBuilder = resources.playbackBuilderFactory?.let { factory ->
            rtpFactory?.let { rtp -> factory(engineScope, cfg.recording.playbackAudioCodec, rtp) }
        }
        // Wave 2 PR-SN-IDENTITY:Manscdp 直走 identityService;
        // Reg/Broadcast/Invite/Playback 4 Coord 既有 lambda 入口由 registerPoolLambdasFrom 派生
        // (过渡桥,Wave 3+ 再迁)。
        val identityService = holders.identityService
        val pool: RegisterPoolLambdas = registerPoolLambdasFrom(identityService)

        val registration = RegistrationCoordinatorImpl(
            config = cfg, transport = tx, scope = engineScope, outbox = outbox,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            cseqProvider = pool.cseqProvider, cseqIncrementer = pool.cseqIncrementer,
            callIdProvider = pool.callIdProvider, callIdSetter = pool.callIdSetter,
            fromTagProvider = pool.fromTagProvider, fromTagSetter = pool.fromTagSetter,
        )
        val broadcast = BroadcastCoordinatorImpl(
            config = cfg, transport = tx, scope = engineScope, outbox = outbox,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            rtpReceiverFactory = resources.rtpReceiverFactory, audioSinkFactory = resources.audioSinkFactory,
            simEventEmit = { ev -> holders.events.emit(ev) },
            cseqProvider = pool.cseqProvider, cseqIncrementer = pool.cseqIncrementer,
            callIdProvider = pool.callIdProvider, callIdSetter = pool.callIdSetter,
            fromTagProvider = pool.fromTagProvider, fromTagSetter = pool.fromTagSetter,
        )
        val playback = PlaybackCoordinatorImpl(
            config = cfg, transport = tx, outbox = outbox, scope = engineScope,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            playbackBuilder = playbackBuilder, recordingService = resources.recordingService,
            simEventEmit = { ev -> holders.events.emit(ev) },
            cseqProvider = pool.cseqProvider, cseqIncrementer = pool.cseqIncrementer,
            callIdProvider = pool.callIdProvider, callIdSetter = pool.callIdSetter,
            fromTagProvider = pool.fromTagProvider, fromTagSetter = pool.fromTagSetter,
        )
        val invite = InviteCoordinatorImpl(
            config = cfg, transport = tx, outbox = outbox, scope = engineScope,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            cameraCapture = resources.cameraCapture, audioCapture = resources.audioCapture,
            rtpSenderFactory = rtpFactory,
            catalogTree = holders.catalogTree, clockOffsetProvider = { holders.clockOffset.value },
            mutableSipState = holders.state, simEventEmit = { ev -> holders.events.emit(ev) },
            cseqProvider = pool.cseqProvider, cseqIncrementer = pool.cseqIncrementer,
            callIdProvider = pool.callIdProvider, callIdSetter = pool.callIdSetter,
            fromTagProvider = pool.fromTagProvider, fromTagSetter = pool.fromTagSetter,
        )
        val manscdp = ManscdpRouterImpl(
            config = cfg, transport = tx, outbox = outbox, scope = engineScope,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            subscriptionRegistry = holders.subscriptionRegistry,
            catalogTree = holders.catalogTree,
            alarmHistoryStore = holders.alarmHistoryStore,
            mutableDeviceControlState = holders.deviceControlState,
            rebootCallback = { engineRefProvider()?.rebootForDeviceControl() ?: Unit },
            requestKeyFrameCallback = { resources.cameraCapture?.requestKeyFrame() },
            broadcastInvoker = broadcast,
            recordingService = resources.recordingService,
            mockGps = holders.mockGps,
            clockOffsetProvider = { holders.clockOffset.value },
            stateRegisteredOrInCall = { holders.state.value == SipState.Registered || holders.state.value == SipState.InCall },
            broadcastBusy = { broadcast.current.value != null },
            simEventEmit = { ev -> holders.events.emit(ev) },
            identityService = identityService,
        )
        return EngineCoordinators(registration, broadcast, playback, invite, manscdp)
    }

    suspend fun cancelConnect() {
        val eng = engine ?: return
        try { eng.cancelRegister() } catch (_: Throwable) {}
        try { eng.shutdown() } catch (_: Throwable) {}
        try { transport?.close() } catch (_: Throwable) {}
        try { snapshotHttp?.close() } catch (_: Throwable) {}
        engine = null
        transport = null
        snapshotHttp = null
    }

    suspend fun disconnect() {
        val eng = engine ?: return
        try { eng.unregister() } catch (_: Throwable) {}
        try { eng.shutdown() } catch (_: Throwable) {}
        try { transport?.close() } catch (_: Throwable) {}
        try { snapshotHttp?.close() } catch (_: Throwable) {}
        engine = null
        transport = null
        snapshotHttp = null
    }

    /**
     * 替换 SimConfig + 持久化。已连接时自动 disconnect → connect cycle。
     */
    suspend fun updateConfig(new: SimConfig) {
        _config.value = new
        runCatching { resources.configStore.save(new) }
        if (engine != null) {
            disconnect()
            connect()
        }
    }

    suspend fun handleNetworkChange(state: NetworkState) {
        engine?.handleNetworkChange(state)
    }

    // ---- Public API 委派(全部转给 engine,无 engine 时 no-op) ----

    suspend fun reportSnapshot() { engine?.reportSnapshot() }
    suspend fun reportAlarm(payload: AlarmPayload) { engine?.reportAlarm(payload) }
    suspend fun localResetAlarm() { engine?.localResetAlarm() }
    suspend fun triggerMediaStatusAbnormal(notifyType: Int) { engine?.triggerMediaStatusAbnormal(notifyType) }
    suspend fun stopStream(reason: String = "user stop") { engine?.stopStream(reason) }
    suspend fun stopBroadcast(reason: BroadcastEndReason = BroadcastEndReason.Local) { engine?.stopBroadcast(reason) }
    fun setBroadcastSpeaker(on: Boolean) {
        engine?.setBroadcastSpeaker(on) ?: run { _broadcastSpeakerOn.value = on }
    }
    suspend fun updateCatalogTree(tree: List<CatalogNode>) {
        holders.catalogTree.value = tree
        engine?.updateCatalogTree(tree)
    }
    suspend fun pushCatalogNotify() { engine?.pushCatalogNotify() }
    suspend fun pushCatalogIncremental(events: List<CatalogChangeEvent>) { engine?.pushCatalogIncremental(events) }
    suspend fun toggleChannelStatus(channelId: String, online: Boolean) {
        engine?.toggleChannelStatus(channelId, online)
    }
    fun consumeEffect() { engine?.consumeEffect() }
    fun updatePoseFromRender(pan: Float, tilt: Float, zoom: Float) {
        engine?.updatePoseFromRender(pan, tilt, zoom)
    }

    /** ViewModel 用来更新 SimConfig 的 in-memory 视图(外部 save 后调,避免重复持久化)。 */
    fun setConfig(new: SimConfig) {
        _config.value = new
    }

    /**
     * 测试可见 — Engine 实例(单测验证内部 state)。
     * 生产代码不应依赖。
     */
    internal fun engineForTest(): SimulatorEngine? = engine
}

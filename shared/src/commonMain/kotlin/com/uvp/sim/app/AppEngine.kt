package com.uvp.sim.app

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmRecord
import com.uvp.sim.domain.BroadcastDialog
import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SimulatorEngine
import com.uvp.sim.domain.SubscriptionSnapshot
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.network.NetworkState
import com.uvp.sim.network.RemoteEndpoint
import com.uvp.sim.network.SipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.network.UdpSipTransport
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AppEngine — 装配根(PR6 T6.3 GREEN)。
 *
 * commonMain 持 Engine 实例 + transport 装配 + 12 个 StateFlow / events 桥接。
 * Android/iOS ViewModel 退化成薄转发,不再持装配逻辑。
 *
 * 决策(plan §1):
 *   - 决策 1 选 A:Engine 实例完全 internal,公开 API 全在 AppEngine
 *   - 决策 2:attachSnapshotPipeline 由 AppEngine 自动调,基于 resources 三件套有无判断
 *   - 决策 3:9 StateFlow 用本地缓存 MutableStateFlow,connect 时 collect engine flow
 *     (跟 ViewModel 当前模式一致,而非 flatMapLatest — 简化迁移)
 *   - 决策 4:ConfigStore interface(plan §1 修订),Android DataStore / iOS NSUserDefaults
 *   - 决策 5:ViewModel 收缩部分,本 PR 只确保 ViewModel 可委派给 AppEngine
 *   - 决策 6:iOS actual 全 null/no-op,M1.1 接 iOS 实现
 *   - 决策 7:handleNetworkChange 暴露 AppEngine,networkController 留 ViewModel
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

    // 9 个 StateFlow + 1 events SharedFlow(Engine 投影到本地)
    private val _state = MutableStateFlow(SipState.Disconnected)
    val state: StateFlow<SipState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SimEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SimEvent> = _events.asSharedFlow()

    private val _subscriptions = MutableStateFlow<Map<String, SubscriptionSnapshot>>(emptyMap())
    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> = _subscriptions.asStateFlow()

    private val _deviceControlState = MutableStateFlow(DeviceControlState())
    val deviceControlState: StateFlow<DeviceControlState> = _deviceControlState.asStateFlow()

    private val _catalogTree = MutableStateFlow<List<CatalogNode>>(emptyList())
    val catalogTree: StateFlow<List<CatalogNode>> = _catalogTree.asStateFlow()

    private val _alarmHistory = MutableStateFlow<List<AlarmRecord>>(emptyList())
    val alarmHistory: StateFlow<List<AlarmRecord>> = _alarmHistory.asStateFlow()

    private val _currentChannelName = MutableStateFlow(initialConfig.device.videoChannelName)
    val currentChannelName: StateFlow<String> = _currentChannelName.asStateFlow()

    private val _clockOffset = MutableStateFlow(ClockOffset.Empty)
    val clockOffset: StateFlow<ClockOffset> = _clockOffset.asStateFlow()

    private val _currentBroadcast = MutableStateFlow<BroadcastDialog?>(null)
    val currentBroadcast: StateFlow<BroadcastDialog?> = _currentBroadcast.asStateFlow()

    private val _broadcastSpeakerOn = MutableStateFlow(true)
    val broadcastSpeakerOn: StateFlow<Boolean> = _broadcastSpeakerOn.asStateFlow()

    /** 初始化 catalogTree(从 SimConfig.catalogTree 取)。connect 后 engine 流覆盖。 */
    init {
        _catalogTree.value = com.uvp.sim.domain.CatalogTreeStore.effectiveTree(initialConfig)
    }

    /**
     * 连接平台(替代 ViewModel.connect 装配)。
     * 关键时序:transport / Engine / snapshotPipeline / 9 collect 桥接 / register。
     */
    suspend fun connect() {
        // 已有 engine 时,Disconnected/Failed 走 retry 路径(同 ViewModel 当前行为)
        val existing = engine
        if (existing != null) {
            when (_state.value) {
                SipState.Registering, SipState.Registered, SipState.InCall -> return
                SipState.Disconnected, SipState.Failed -> {
                    try {
                        transport?.connect()
                        existing.register()
                    } catch (e: Throwable) {
                        _events.emit(SimEvent.TransportError("register retry: ${e.message}"))
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

        // RtpSender factory 用 resources 工厂封装一层补 scope
        val rtpFactory: ((String, Int, com.uvp.sim.network.RtpMode) -> com.uvp.sim.network.RtpSender)? =
            resources.rtpSenderFactory?.let { f -> { host, port, mode -> f(host, port, engineScope, mode) } }

        val playbackBuilder = resources.playbackBuilderFactory?.let { factory ->
            rtpFactory?.let { rtp -> factory(engineScope, cfg.recording.playbackAudioCodec, rtp) }
        }

        val eng = SimulatorEngine(
            config = cfg,
            transport = tx,
            scope = engineScope,
            localIpProvider = resources.localIpProvider,
            localPortProvider = { tx.localPort.takeIf { it > 0 } ?: 5060 },
            cameraCapture = resources.cameraCapture,
            audioCapture = resources.audioCapture,
            rtpSenderFactory = rtpFactory,
            recordingService = resources.recordingService,
            playbackBuilder = playbackBuilder,
            rtpReceiverFactory = resources.rtpReceiverFactory,
            audioSinkFactory = resources.audioSinkFactory,
        )
        engine = eng

        // Snapshot pipeline 三件套都有时自动 attach
        val capture = resources.snapshotCapture
        val cache = resources.snapshotCache
        val httpEngine = resources.httpEngineFactory?.invoke()
        if (capture != null && cache != null && httpEngine != null) {
            val http = HttpClient(httpEngine)
            snapshotHttp = http
            eng.attachSnapshotPipeline(capture, cache, http)
            engineScope.launch { runCatching { cache.gc() } }
        }

        // 9 个 collect 桥接(从 ViewModel.connect 迁过来)
        engineScope.launch { eng.state.collect { _state.value = it } }
        engineScope.launch { eng.events.collect { _events.emit(it) } }
        engineScope.launch { eng.subscriptions.collect { _subscriptions.value = it } }
        engineScope.launch { eng.deviceControlState.collect { _deviceControlState.value = it } }
        engineScope.launch { eng.catalogTree.collect { _catalogTree.value = it } }
        engineScope.launch { eng.alarmHistory.collect { _alarmHistory.value = it } }
        engineScope.launch { eng.currentChannelName.collect { _currentChannelName.value = it } }
        engineScope.launch { eng.clockOffset.collect { _clockOffset.value = it } }
        engineScope.launch { eng.currentBroadcast.collect { _currentBroadcast.value = it } }
        engineScope.launch { eng.broadcastSpeakerOn.collect { _broadcastSpeakerOn.value = it } }

        try {
            tx.connect()
            eng.register()
        } catch (e: Throwable) {
            _events.emit(SimEvent.TransportError("connect: ${e::class.simpleName}: ${e.message}"))
        }
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
        _catalogTree.value = tree
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

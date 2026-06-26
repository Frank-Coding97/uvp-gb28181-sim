package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Top-level orchestrator — 持 5 Coord 引用 + 路由 dispatch + 3 bridge job + 公开 API delegate。
 * 装配下沉到 AppEngine.buildCoordinators / buildHolders(P1.5),Engine 不再 own holder/Coord 实例。
 * UI 订阅 [state] / [events],动作走 [register] / [unregister]。Single-instance per session。
 */
class SimulatorEngine internal constructor(
    private val config: SimConfig,
    private val transport: SipTransport,
    scope: CoroutineScope,
    @Suppress("unused") private val resources: com.uvp.sim.app.PlatformResources,
    private val coordinators: EngineCoordinators,
    private val holders: EngineHolders,
) {
    private val scope: CoroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    private val registration get() = coordinators.registration
    private val broadcast get() = coordinators.broadcast
    private val playback get() = coordinators.playback
    private val invite get() = coordinators.invite
    private val manscdp get() = coordinators.manscdp

    val state: StateFlow<SipState> = holders.state.asStateFlow()
    val clockOffset: StateFlow<ClockOffset> = holders.clockOffset.asStateFlow()
    val events: SharedFlow<SimEvent> = holders.events.asSharedFlow()
    /**
     * Wave 3 PR-DC-DECOUPLE:UI 仍订阅 [DeviceControlState] 兼容 wrapper,内部 holder 已切到
     * [DeviceControlModel];这里同步派生 wrapper(model + render),保持 UI 契约不破。
     * 用 [DerivedDeviceControlStateFlow] 零协程映射,避免 `stateIn(Eagerly)` 在 runTest 里污染 testScope。
     */
    @Suppress("DEPRECATION")
    val deviceControlState: StateFlow<DeviceControlState> =
        DerivedDeviceControlStateFlow(holders.deviceControlState.asStateFlow())
    /** 业务 / 测试新路径:直接订阅 [DeviceControlModel] 流(无 render 派生开销)。 */
    val deviceControlModel: StateFlow<DeviceControlModel> = holders.deviceControlState.asStateFlow()
    val alarmHistory: StateFlow<List<AlarmRecord>> = holders.alarmHistoryStore.history
    val catalogTree: StateFlow<List<com.uvp.sim.config.CatalogNode>> = holders.catalogTree.asStateFlow()
    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> = holders.subscriptionRegistry.subscriptions

    /** UI 消费 [DeviceEffect] 后清零 pendingEffect 防重复触发(Compose `LaunchedEffect`)。 */
    fun consumeEffect() {
        holders.deviceControlState.update { it.copy(pendingEffect = null) }
    }

    /** GlbSceneState ~10 帧/166ms 回写一次最新 PTZ pose,供预置位 SET 取真实值。 */
    fun updatePoseFromRender(pan: Float, tilt: Float, zoom: Float) {
        holders.deviceControlState.update { it.copy(panAngle = pan, tiltAngle = tilt, zoomLevel = zoom) }
    }

    private val mutex = Mutex()
    private var inboundJob: Job? = null

    /** 当前推流通道的显示名 — 委派给 InviteCoordinator(PR4 T4.3)。 */
    val currentChannelName: StateFlow<String> get() = invite.currentChannelName

    /** Engine 不在 InCall 时,把 registration.state 单向直写到 holders.state(InCall 由业务路径维护)。 */
    private val registrationStateBridge: Job = scope.launch {
        registration.state.collect { regState ->
            if (holders.state.value != SipState.InCall) holders.state.value = mapRegistrationState(regState)
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
            if (sim != null) holders.events.emit(sim)
        }
    }
    private val registrationClockBridge: Job = scope.launch {
        registration.clockOffset.collect { off -> holders.clockOffset.value = off }
    }

    /** Initiate registration. Returns immediately; observe [state] for completion. */
    suspend fun register() {
        startInboundIfNeeded()
        holders.events.emit(SimEvent.RegistrationStarted("${config.server.ip}:${config.server.port}"))
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
        holders.subscriptionRegistry.cancelAll()
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
                holders.events.emit(SimEvent.NetworkBound(newState.preference.name, newState.interfaceName, newState.localIp))
                SystemLogger.emit(LogLevel.Info, LogTag.Network,
                    "网络已切到 ${newState.preference.name} 接口 ${newState.interfaceName} IP=${newState.localIp},触发重注册")
                triggerReregisterIfActive()
            }
            com.uvp.sim.network.NetworkState.Auto -> {
                holders.events.emit(SimEvent.NetworkAuto)
                SystemLogger.emit(LogLevel.Info, LogTag.Network, "网络偏好 → 自动,触发重注册以刷新 Contact 头")
                triggerReregisterIfActive()
            }
            is com.uvp.sim.network.NetworkState.Unavailable -> {
                holders.events.emit(SimEvent.NetworkUnavailable(newState.reason))
                SystemLogger.emit(LogLevel.Warning, LogTag.Network, "网络不可用: ${newState.reason}")
            }
            is com.uvp.sim.network.NetworkState.Switching -> Unit
        }
    }

    /** 在线时才驱动 unregister → register;Disconnected/Failed 不替老板决定。 */
    private suspend fun triggerReregisterIfActive() {
        val cur = holders.state.value
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
            holders.subscriptionRegistry.cancelAll()
            inboundJob?.cancel(); inboundJob = null
            holders.state.value = SipState.Disconnected
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
                    holders.events.emit(SimEvent.MessageReceived(msg))
                    try { handleIncoming(msg) } catch (e: Throwable) {
                        holders.events.emit(SimEvent.TransportError("handleIncoming: ${e::class.simpleName}: ${e.message}"))
                    }
                }
            } catch (e: Throwable) {
                holders.events.emit(SimEvent.TransportError("inbound: ${e::class.simpleName}: ${e.message}"))
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
                holders.events.emit(SimEvent.HeartbeatAcknowledged(0))
            }
            else -> Unit
        }
    }

    /** Coord 改完状态后立即同步,避免 bridge 协程时序滞后让后续消息读到陈旧状态。 */
    private fun syncStateFromRegistration() {
        if (holders.state.value == SipState.InCall) return
        holders.state.value = mapRegistrationState(registration.state.value)
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

    /** DeviceControl TeleBoot 回调(followup A 注入 Manscdp,P1.5 仍保留为 Engine internal 方法,AppEngine 装配时引用)。 */
    internal suspend fun rebootForDeviceControl() {
        SystemLogger.emit(LogLevel.Info, LogTag.Lifecycle, "TeleBoot → 重新注册")
        try { unregister() } catch (_: Throwable) { /* 平台可能已不可达 */ }
        delay(1_000L)
        register()
    }
}

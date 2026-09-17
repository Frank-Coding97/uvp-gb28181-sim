package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.coord.RegistrationEvent
import com.uvp.sim.domain.coord.RegistrationState
import com.uvp.sim.domain.devicecontrol.nowMs
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.SipMessageRouter
import com.uvp.sim.sip.SipMessageRouterImpl
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
     * Wave 4 PR-UI-PROTOCOL-FIX:UI 直接订阅 [DeviceControlModel](业务模型),
     * 渲染层语义字段(含 [DeviceCommandCategory])由 UI Mapper 调 [deriveRenderState] 派生。
     * 旧 `DeviceControlState` 兼容 wrapper + `DerivedDeviceControlStateFlow` 已删除。
     */
    val deviceControlState: StateFlow<DeviceControlModel> = holders.deviceControlState.asStateFlow()
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

    fun adjustLocalPtzPosition(panDelta: Float, tiltDelta: Float, zoomDelta: Float) {
        holders.deviceControlState.update {
            it.adjustLocalPtzPosition(panDelta, tiltDelta, zoomDelta)
        }
    }

    /**
     * 本机(而非平台)推光圈/聚焦的行程增量,范围 0~1。
     *
     * 与 [adjustLocalPtzPosition] 分开是因为云台三轴和 FI 两轴的**语义单位不同**
     * (前者的 pan/tilt 是角度、zoom 是倍率;后者是归一化行程 0~1),合成一个签名
     * 只会让调用方每次都要传三个 0。落库仍是同一个 `DeviceControlModel`。
     */
    fun adjustLocalLensPosition(focusDelta: Float, irisDelta: Float) {
        holders.deviceControlState.update {
            it.adjustLocalPtzPosition(0f, 0f, 0f, focusDelta, irisDelta)
        }
    }

    private val mutex = Mutex()
    private var inboundJob: Job? = null

    /** 当前推流通道的显示名 — 委派给 InviteCoordinator(PR4 T4.3)。 */
    val currentChannelName: StateFlow<String> get() = invite.currentChannelName

    /** 只表示平台已建立 INVITE 实时流，单纯 SIP 注册不计入。 */
    val activeLiveStream: StateFlow<Boolean> = invite.activeStreamSnapshot
        .map { it != null }
        .stateIn(this.scope, SharingStarted.Eagerly, false)

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
                is RegistrationEvent.TransportFailed -> SimEvent.TransportError(ev.reason)
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

    /**
     * 看守位自动归位 —— GB/T 28181 里 `ResetTime` 的语义是"无云台操作等待该秒数后归位到
     * `PresetIndex`",这是**设备行为**不是配置数据。平台把配置下发下来之后,期待发生的就是
     * 这件事;不实现它,界面上就只是个数字,演示时什么也不会发生。
     *
     * 用秒级轮询而不是"每条 PTZ 命令挂一个定时器":云台操作有四个入口(平台方向命令、
     * 平台精准定位、本机方向盘、本机光圈/聚焦长按键),每个入口都要"取消旧倒计时 + 重排";
     * 挂定时器要在这四处各写一遍,漏一处就出现"刚推完就被拉回去"。轮询只要一个活动信号。
     *
     * 活动信号 = (最后一条命令的时间戳, 当前是否正在转)。第二位是为了覆盖**本机手操** ——
     * 它走 `adjustLocalPtzPosition`,不写 `lastCommand`,只看时间戳的话人手正在转云台时
     * 倒计时照走,松手前就被硬拉回看守位。
     *
     * 判定逻辑本身是纯函数 [decideHomePositionReturn],这里只负责喂参数与落动作。
     */
    private val homePositionAutoReturnJob: Job = scope.launch {
        var observed = CameraActivity(0L, false)
        var idleSeconds = 0L
        var returned = false
        while (true) {
            delay(HOME_POSITION_TICK_MS)
            val model = holders.deviceControlState.value
            val activity = CameraActivity(model.lastCommand?.timestampMs ?: 0L, model.isCameraMoving)
            if (activity != observed) {
                // 有人动了云台 → 倒计时归零,并重新武装下一次归位。
                observed = activity
                idleSeconds = 0
                returned = false
                continue
            }
            idleSeconds++
            val plan = decideHomePositionReturn(model, idleSeconds, returned) ?: continue
            returned = true
            applyHomePositionReturn(plan, idleSeconds)
            // 归位自己写进去的 lastCommand 也算一次"活动信号变化"。不在这里同步掉的话,
            // 下一拍会把它误认成新的云台操作 → 清零倒计时 + 重新武装 → 每 ResetTime 秒
            // 再归一次位,无限循环刷日志。
            observed = CameraActivity(
                holders.deviceControlState.value.lastCommand?.timestampMs ?: 0L,
                false,
            )
        }
    }

    private fun applyHomePositionReturn(plan: HomePositionReturnPlan, idleSeconds: Long) {
        // 归位是**唯一由设备自己发起**的动作,平台侧看不到、SIP 上也没有报文,不看日志就
        // 完全无从判断它有没有发生(现场排障时尤其致命:画面动了却不知道是谁动的)。
        // 而且它是周期性的,若那条 `returned` 闩失效,这里会**每 ResetTime 秒刷一条** ——
        // 日志本身就是那个回归的观测点。
        SystemLogger.emit(
            LogLevel.Info,
            LogTag.Media,
            "看守位自动归位 → P${plan.presetIndex} (空闲 ${idleSeconds}s) " +
                "pan=${plan.target.pan} tilt=${plan.target.tilt} zoom=${plan.target.zoom}",
        )
        holders.deviceControlState.update {
            it.copy(
                panAngle = plan.target.pan,
                tiltAngle = plan.target.tilt,
                zoomLevel = plan.target.zoom,
                currentPresetIndex = plan.presetIndex,
                // 用 HomePositionReturn 而不是 PresetRecall:两个 effect 在三个渲染端各有
                // 独立分支,前者就是为"看守位归位"准备的(它不区分来源是自动还是平台下发)。
                pendingEffect = DeviceEffect.HomePositionReturn(plan.target),
                lastCommand = LastDeviceCommand(
                    "HomePosition",
                    "AutoReturn#${plan.presetIndex}",
                    nowMs(),
                ),
            )
        }
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
        // cross-review R3 #3 verify-followup — 先等 manscdp 内 pending sync job(cancelAll 触发的
        // location provider stop)完成,再 cancel scope。否则 scope.cancel() 会砍掉 fire-and-forget
        // 的 stop 协程,provider 状态泄漏到下一 session。
        manscdp.shutdown()
        registrationStateBridge.cancel(); registrationEventBridge.cancel(); registrationClockBridge.cancel()
        homePositionAutoReturnJob.cancel()
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

    /** cross-review R1 #3 修复 — Android/iOS 授权后触发 location lifecycle 重同步(见 [ManscdpRouter.resyncLocationLifecycle])。 */
    suspend fun resyncLocationLifecycle() = manscdp.resyncLocationLifecycle()

    suspend fun updateCatalogTree(tree: List<com.uvp.sim.config.CatalogNode>) = manscdp.updateCatalogTree(tree)
    suspend fun pushCatalogNotify() = manscdp.pushCatalogNotify()
    suspend fun pushCatalogIncremental(events: List<com.uvp.sim.config.CatalogChangeEvent>) =
        manscdp.pushCatalogIncremental(events)
    suspend fun toggleChannelStatus(channelId: String, online: Boolean) =
        manscdp.toggleChannelStatus(channelId, online)

    /** 5.5 device-initiated BYE — Invite 域。 */
    suspend fun stopStream(reason: String = "user stop") = invite.stopStream(reason)

    fun onAppBackground() = invite.onAppBackground()
    fun onAppForeground() = invite.onAppForeground()

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

    /**
     * SIP 消息中央路由(Wave 4 PR-D / P2-2):Engine 不再 own SIP method 大型 switch,
     * 全部下放到 [SipMessageRouterImpl]。Engine 在 handleIncoming 里只调一次 route。
     */
    private val messageRouter: SipMessageRouter = SipMessageRouterImpl(
        registration = registration,
        invite = invite,
        broadcast = broadcast,
        playback = playback,
        manscdp = manscdp,
        onRegistrationStateChanged = { syncStateFromRegistration() },
        onMessage2xxAck = { holders.events.emit(SimEvent.HeartbeatAcknowledged(0)) },
    )

    private fun startInboundIfNeeded() {
        if (inboundJob != null) return
        inboundJob = scope.launch {
            try {
                transport.incoming.collect { envelope ->
                    holders.events.emit(SimEvent.MessageReceived(envelope.message))
                    try { handleIncoming(envelope) } catch (e: Throwable) {
                        holders.events.emit(SimEvent.TransportError("handleIncoming: ${e::class.simpleName}: ${e.message}"))
                    }
                }
            } catch (e: Throwable) {
                holders.events.emit(SimEvent.TransportError("inbound: ${e::class.simpleName}: ${e.message}"))
            }
        }
    }

    private suspend fun handleIncoming(envelope: com.uvp.sim.network.SipEnvelope) {
        messageRouter.route(envelope)
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

    /** DeviceControl TeleBoot 回调(followup A 注入 Manscdp,P1.5 仍保留为 Engine internal 方法,AppEngine 装配时引用)。 */
    internal suspend fun rebootForDeviceControl() {
        SystemLogger.emit(LogLevel.Info, LogTag.Lifecycle, "TeleBoot → 重新注册")
        try { unregister() } catch (_: Throwable) { /* 平台可能已不可达 */ }
        delay(1_000L)
        register()
    }
}

/**
 * 看守位自动归位的巡检间隔。
 *
 * 1 秒足够:`ResetTime` 最小 10 秒(平台侧 `UpdatePTZHomePosition` 的校验),1 秒粒度下最多
 * 晚归位 1 秒,肉眼看不出来;更密只是白跑协程。也正因为间隔恰好是 1 秒,`idleSeconds`
 * 可以直接用拍数累加,不需要另读时钟。
 */
private const val HOME_POSITION_TICK_MS = 1_000L

/**
 * 云台"活动信号" —— 用来给看守位倒计时复位。
 *
 * 两个分量缺一不可:时间戳覆盖**平台**下发(每条命令都会写 `lastCommand`),`moving` 位覆盖
 * **本机**手操(走 `adjustLocalPtzPosition`,不写 `lastCommand`)。只看时间戳的话,人正按住
 * 方向键转云台时倒计时照走,松手前就会被硬拉回看守位。
 */
private data class CameraActivity(val lastCommandAt: Long, val moving: Boolean)

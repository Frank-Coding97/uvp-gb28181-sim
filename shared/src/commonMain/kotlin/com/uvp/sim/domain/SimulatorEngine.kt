package com.uvp.sim.domain

import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.coord.RegistrationEvent
import com.uvp.sim.domain.coord.RegistrationState
import com.uvp.sim.domain.devicecontrol.nowMs
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.RportObservation
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
    /**
     * 附录 I 协商结果:平台在注册响应里声明的协议版本(null = 未声明/不可识别)。
     * UI 用它显示「本机声明 × 平台声明 → 有效版本」,联调时一眼看出两侧是否真在协商。
     */
    val platformVersion: StateFlow<GbVersion?> = holders.platformVersion.asStateFlow()
    /**
     * 平台视角的我方端点 + 是否经地址转换(null = 平台未回填,不代表不在 NAT 后)。
     * UI 用它显示「平台看到的你是 X」,并在判定为 NAT 时提示改用 TCP(§9.1.1 f)。
     */
    val rportObservation: StateFlow<RportObservation?> = holders.rportObservation.asStateFlow()
    /**
     * SIP 长连接自愈状态(null = 未在重连)。见 [ReconnectAttempt]。
     */
    val reconnect: StateFlow<ReconnectAttempt?> = holders.reconnect.asStateFlow()
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
    /** 附录 I:注册协调器解析出的平台协议版本桥接到 holders,供 UI 与出站报文版决策读。 */
    private val registrationVersionBridge: Job = scope.launch {
        registration.platformVersion.collect { ver -> holders.platformVersion.value = ver }
    }

    /** §9.1.1 f 的适用性判据:平台看到的我方端点(经地址转换时与本地不同)桥接到 holders。 */
    private val rportObservationBridge: Job = scope.launch {
        registration.rportObservation.collect { observation -> holders.rportObservation.value = observation }
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

    /**
     * 巡航执行 —— `0x88 开始巡航` 之后的**设备自主行为**。
     *
     * 标准只给了"开始巡航 + 组号"，**没有任何执行进度的上报**：设备得自己按那条轨迹的点位链
     * 依次转过去、每个点停留 `StayTime` 秒。不实现这一段，平台那边只会显示"启动指令已下发"，
     * 而设备画面**纹丝不动** —— 用户 2026-09-17 报的「调用之后模拟器模拟设备不动」就是它
     * （同一类缺口，看守位归位当时也是这么补的，见 [homePositionAutoReturnJob]）。
     *
     * 秒级轮询，与 [homePositionAutoReturnJob] 同构：判定本身是纯函数 [cruiseStepAt]，
     * 这里只做「起步/停车的边沿判定 + 计时 + 落动作」。
     *
     * 起步**立即**走第一个点（不等 `dwell`）：`dwell` 的语义是"到点后停留多久再走下一个"，
     * 不含起步。等一个 dwell 再动的话，操作员点完按钮要干看默认 30 秒画面才有变化。
     */
    private val cruiseExecutionJob: Job = scope.launch {
        var runningTrack: Int? = null
        var stepIndex = 0
        var secondsOnStep = 0L
        while (true) {
            delay(CRUISE_TICK_MS)
            val model = holders.deviceControlState.value
            val trackNum = model.activeCruiseTrack
            if (trackNum == null) {
                // 停了（平台发的全零停止指令 / 0x88 组号 0）。清干净，下次启动从第 0 步重来。
                runningTrack = null
                stepIndex = 0
                secondsOnStep = 0
                continue
            }
            if (trackNum != runningTrack) {
                // 起步，或平台在巡航途中换了另一条轨迹 —— 两种都从新轨迹的第 0 步重新开始。
                // （换轨必须重置步号，否则会拿着 A 的步号去索引 B 的点位链。）
                runningTrack = trackNum
                stepIndex = 0
                secondsOnStep = 0
                val first = cruiseStepAt(model, 0)
                if (first == null) {
                    // 启动成功但一步都走不了：本机一个该轨迹引用的预置位都没有。
                    // 只在起步这一刻报一次（下一拍起走 `current == null` 分支，不再刷日志）。
                    SystemLogger.emit(
                        LogLevel.Warning,
                        LogTag.Media,
                        "巡航 #$trackNum 启动但本机无可用点位（该轨迹引用的预置位在本机都不存在），停在原地",
                    )
                    continue
                }
                applyCruiseStep(first)
                continue
            }
            val current = cruiseStepAt(model, stepIndex)
            if (current == null) {
                // 轨迹被删空 / 点位全没了。不动，也不刷日志。
                secondsOnStep = 0
                continue
            }
            secondsOnStep++
            if (secondsOnStep < current.dwellSeconds) continue
            secondsOnStep = 0
            stepIndex++
            val next = cruiseStepAt(model, stepIndex)
            if (next == null) {
                secondsOnStep = 0
                continue
            }
            applyCruiseStep(next)
        }
    }

    private fun applyCruiseStep(plan: CruiseStepPlan) {
        // 巡航是**设备自发**的动作：平台只看得到自己发出去的那条启动指令，SIP 上没有任何报文能
        // 证明"设备真的在巡"。不写日志的话，现场排障时画面在动却不知道是谁在动 —— 同
        // `applyHomePositionReturn` 的取舍。这行同时是"节拍有没有跑偏"的观测点。
        SystemLogger.emit(
            LogLevel.Info,
            LogTag.Media,
            "巡航 #${plan.trackNum} 第 ${plan.pointIndex + 1}/${plan.pointCount} 个点 → P${plan.presetIndex} " +
                "pan=${plan.target.pan} tilt=${plan.target.tilt} zoom=${plan.target.zoom} " +
                "(停留 ${plan.dwellSeconds}s)",
        )
        holders.deviceControlState.update {
            it.copy(
                panAngle = plan.target.pan,
                tiltAngle = plan.target.tilt,
                zoomLevel = plan.target.zoom,
                currentPresetIndex = plan.presetIndex,
                // 复用 PresetRecall 而不是新增一个 effect：巡航的每一步在**设备侧就是一次预置位
                // 调用**，三个渲染端都已接了它（ease 到该点位姿 + 高亮对应 chip），语义完全对得上。
                pendingEffect = DeviceEffect.PresetRecall(plan.presetIndex, plan.target),
                // 写 lastCommand 有两个作用：① HUD 自动切到「云台」页并显示这一步的动作；
                // ② 它同时是看守位的**活动信号**，让空闲倒计时在整段巡航期间保持清零
                //    （另有 [decideHomePositionReturn] 里那道"巡航中不归位"的硬闸）。
                lastCommand = LastDeviceCommand(
                    "PTZCmd",
                    "巡航 #${plan.trackNum} 第 ${plan.pointIndex + 1}/${plan.pointCount} 个点 → P${plan.presetIndex}",
                    nowMs(),
                ),
            )
        }
    }

    /**
     * 自动扫描执行 —— `0x89 开始扫描`(GB/T 28181 表 A.10)之后的**设备自主行为**。
     *
     * 跟 [cruiseExecutionJob] 同构(秒级/拍级轮询 + 纯函数判定 + 起步·停止边沿),但有两处
     * **不能照抄**:
     *
     *  1. **写的是速率,不是姿态**。巡航每拍是"跳到一个预置位"(ease 到某个坐标);扫描是
     *     **连续横扫**,没有目标点。所以这一拍只判定"往哪边、多快"([scanStepAt]),
     *     然后把速率写进 `Model.panSpeed` —— 渲染端本来就在逐帧按「速率 × 时间」积分姿态
     *     (Android `CameraGlbView` / iOS native),于是两侧画面都是平滑横扫而不是逐拍跳格。
     *  2. **节拍更快**:150ms(巡航是 1 秒)。巡航每拍一次跳转,扫描每拍只确认一次"到边界了没",
     *     节拍太慢会让掉头滞后得肉眼可见。
     *
     * 起步方向取**向右**:表 A.10 注4「自动扫描开始时,整体画面从右向左移动」——
     * 画面里的景物往左走,说明云台在往右转(`panAngle` 增大)。
     */
    private val scanExecutionJob: Job = scope.launch {
        var runningGroup: Int? = null
        var direction = ScanSweepDirection.TO_RIGHT
        while (true) {
            delay(SCAN_TICK_MS)
            val model = holders.deviceControlState.value
            val groupNum = model.activeScanGroup
            if (groupNum == null) {
                // 停了(全零停止帧 / 被开始巡航顶替)。清干净,下次启动重新按注4 从向右开始。
                if (runningGroup != null) {
                    runningGroup = null
                    direction = ScanSweepDirection.TO_RIGHT
                    clearScanPanRate()
                }
                continue
            }
            val plan = scanStepAt(model, direction)
            if (plan == null) {
                if (runningGroup != groupNum) {
                    // 启动了但一步都扫不了:左右边界没设全(或跨度不足)。
                    // 只在起步这一刻报一次(下一拍起走 `plan == null` 分支不再刷日志)。
                    runningGroup = groupNum
                    clearScanPanRate()
                    SystemLogger.emit(
                        LogLevel.Warning,
                        LogTag.Media,
                        "扫描 #$groupNum 启动但本机缺左右边界(或跨度不足),停在原地——" +
                            "平台需要先设左/右边界再开始扫描",
                    )
                }
                continue
            }
            if (runningGroup != groupNum) {
                runningGroup = groupNum
                SystemLogger.emit(
                    LogLevel.Info,
                    LogTag.Media,
                    "扫描 #$groupNum 启动:${scanGroupText(model, groupNum)}(注4:画面自右向左移动)",
                )
            }
            if (plan.turnedAround) {
                SystemLogger.emit(
                    LogLevel.Info,
                    LogTag.Media,
                    "扫描 #$groupNum 到达${if (plan.direction == ScanSweepDirection.TO_RIGHT) "左" else "右"}边界,掉头",
                )
            }
            direction = plan.direction
            applyScanPanRate(plan.panRateDegPerSec)
        }
    }

    /**
     * 把横扫速率写进 Model。只在**值真的变了**时才 `copy` —— `MutableStateFlow` 对相等值
     * 本来就不发射,这里显式挡一道是为了不每 150ms 造一个新对象喂给 UI 层。
     */
    private fun applyScanPanRate(rate: Float) {
        holders.deviceControlState.update { if (it.panSpeed == rate) it else it.copy(panSpeed = rate) }
    }

    /**
     * 收尾时把扫描占着的水平速率清掉。
     *
     * ⛔ 不能只清 `activeScanGroup` 就完事:速率留在 Model 里,渲染端会**一直按它积分下去** ——
     * 表现为"扫描停了、镜头却还在往一侧转"。扫描被开始巡航顶替时尤其明显(巡航 ease 完
     * 又被这个残留速率带跑)。
     */
    private fun clearScanPanRate() {
        holders.deviceControlState.update { if (it.panSpeed == 0f) it else it.copy(panSpeed = 0f) }
    }

    /** Initiate registration. Returns immediately; observe [state] for completion. */
    suspend fun register() {
        startInboundIfNeeded()
        startReconnectIfNeeded()
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
        cruiseExecutionJob.cancel()
        scanExecutionJob.cancel()
        // 显式 stop 而不是只靠下面的 scope.cancel():stop() 会把 holder 上的"正在重连"
        // 清成 null。只 cancel scope 的话那个标记会留在 UI 上,而 engine 重建后**没人会清它**
        // (新的监管器只在自己的 recover 流程里写),横幅就一直挂着"正在重连"。
        reconnectSupervisor.stop()
        registrationVersionBridge.cancel()
        rportObservationBridge.cancel()
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

    /**
     * SIP 长连接自愈(GB/T 28181 §5.2 传输层)。见 [SipReconnectSupervisor] 的职责边界说明。
     *
     * **为什么挂在 Engine 而不挂在 AppEngine**:监管器的寿命必须等于"这一次会话"——
     * 用户注销 / 改配置走的是 `AppEngine.disconnect → engine.shutdown() → scope.cancel()`,
     * 挂在这里它就自然停;若挂在 AppEngine,用户点了注销之后它还会把连接拉起来,
     * 现象是「点了注销,几秒后设备自己又注册回来了」,而日志上看是设备主动行为,极难归因。
     */
    private val reconnectSupervisor = SipReconnectSupervisor(
        transport = transport,
        scope = scope,
        onSessionLost = { loss -> onTransportLost(loss) },
        register = { this@SimulatorEngine.register() },
        emitEvent = { ev -> holders.events.emit(ev) },
        onAttemptChanged = { attempt -> holders.reconnect.value = attempt },
    )

    /**
     * 连接被对端打死后、重建连接**之前**的会话收尾。
     *
     * ⛔ 顺序不能换,两步各堵一个坑:
     * 1. **先停活跃流**:INVITE 会话已经随连接死了,但 [holders.state] 还停在 `InCall`,
     *    而 [registrationStateBridge] 只在"当前不是 InCall"时才把注册状态写过去。
     *    不先摘掉这个闩,注册状态后面就算被作废成 Disconnected,UI 也**永远读不到**,
     *    横幅会一直显示「设备已注册 · LIVE」—— 这正是这次要修掉的那句谎话。
     *    (同款处置见 [registrationEventBridge] 收到 AutoReregisterTriggered 的分支。)
     * 2. **再作废注册会话**:`RegistrationCoordinatorImpl.register()` 开头有
     *    "状态仍是 Registered 就 return" 的守卫 —— 不作废的话重连流程会跑完、
     *    TCP 也真连上了,但**一次 REGISTER 都不会发出去**。
     */
    private suspend fun onTransportLost(loss: com.uvp.sim.network.ConnectionLost) {
        val reason = loss.reason.label
        // 这三条 stop 都会尝试发 BYE / 收尾报文,而连接已经死了 —— 失败是预期内的,
        // 不能让它把后面的会话作废流程带崩。没有活跃流时它们在入口就返回(不发报文)。
        runCatching { invite.stopStream("SIP 连接断开: $reason") }
            .onFailure { logSessionCleanupFailure("invite", it) }
        runCatching { broadcast.stop(BroadcastEndReason.Error) }
            .onFailure { logSessionCleanupFailure("broadcast", it) }
        runCatching { playback.stop("SIP 连接断开") }
            .onFailure { logSessionCleanupFailure("playback", it) }
        registration.onConnectionLost()
    }

    private fun logSessionCleanupFailure(domain: String, error: Throwable) {
        SystemLogger.emit(
            LogLevel.Warning,
            LogTag.Network,
            "断连收尾:停 $domain 失败(连接已死,预期内): ${error::class.simpleName}: ${error.message}",
        )
    }

    private fun startReconnectIfNeeded() {
        // 幂等(UDP 无连接可断,connectionLost 是空流 → 起来也只是挂着一个空 collect)。
        reconnectSupervisor.start()
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
 * 巡航执行的巡检间隔。
 *
 * 1 秒的理由与看守位不同：那边是"最小 `ResetTime` 10 秒，1 秒粒度足够"；这边是**停留时间**
 * 的粒度 —— 平台写侧允许 1-4095 秒、演示里常用 5 秒，1 秒粒度意味着最多晚一步 1 秒。
 * 更密（比如 100ms）只是白跑协程：整条链的节拍由 `dwellTime` 决定，设备不会因为查得勤就动得快。
 */
private const val CRUISE_TICK_MS = 1_000L

/**
 * 自动扫描的节拍间隔。
 *
 * 150ms 的理由与巡航不同:巡航每拍是"跳到一个点位"(画面由 ease 动画演),扫描每拍只是
 * **重新确认方向**(姿态由渲染端按 `panSpeed` 逐帧积分)。拍子太慢,"到边界掉头"会滞后得
 * 肉眼可见(掉头点明显越过边界);太快只是白跑协程 —— 渲染端回写姿态是 ~166ms 一次的节流,
 * 比它更密也读不到更新的位置。
 */
private const val SCAN_TICK_MS = 150L

/**
 * 扫描组的"左右边界 + 速度"一行文案,只用于**设备自主行为**的日志。
 *
 * ⚠️ 与巡航/看守位那两条日志同一个取舍:扫描在 SIP 上只有平台那一条"开始"指令,之后设备扫成
 * 什么样平台一无所知,而且附录 A 里**没有任何查询命令能回读边界**(没有 `ScanQuery`)——
 * 不看日志就完全无法判断设备侧到底有没有那对边界。"未设"两个字是刻意留的:
 * 它把"边界没设全"与"边界设了但跨度不足"这两种都会让扫描原地不动的原因区分开。
 */
private fun scanGroupText(model: DeviceControlModel, groupNum: Int): String {
    val group = model.scanGroups[groupNum]
    fun angle(value: Float?): String =
        value?.let { "${kotlin.math.round(it).toInt()}°" } ?: "未设"
    return "左 ${angle(group?.leftBoundary?.pan)} ↔ 右 ${angle(group?.rightBoundary?.pan)}" +
        " · 速度 ${group?.speed ?: "未下发"}"
}

/**
 * 云台"活动信号" —— 用来给看守位倒计时复位。
 *
 * 两个分量缺一不可:时间戳覆盖**平台**下发(每条命令都会写 `lastCommand`),`moving` 位覆盖
 * **本机**手操(走 `adjustLocalPtzPosition`,不写 `lastCommand`)。只看时间戳的话,人正按住
 * 方向键转云台时倒计时照走,松手前就会被硬拉回看守位。
 */
private data class CameraActivity(val lastCommandAt: Long, val moving: Boolean)

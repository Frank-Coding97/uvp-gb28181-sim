package com.uvp.sim.app

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmHistoryStore
import com.uvp.sim.domain.AlarmRecord
import com.uvp.sim.domain.BroadcastDialog
import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.EngineCoordinators
import com.uvp.sim.domain.EngineHolders
import com.uvp.sim.domain.MockGpsSource
import com.uvp.sim.domain.ProtocolClock
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.ReconnectAttempt
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SimulatorEngine
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.domain.SubscriptionSnapshot
import com.uvp.sim.domain.VirtualStorageCards
import com.uvp.sim.domain.newDefaultIdentityService
import com.uvp.sim.domain.coord.BroadcastCoordinatorImpl
import com.uvp.sim.domain.coord.InviteCoordinatorImpl
import com.uvp.sim.domain.coord.ManscdpRouterImpl
import com.uvp.sim.domain.coord.PlaybackCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationCoordinatorImpl
import com.uvp.sim.domain.withDeviceConfig
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.FrontOsdState
import com.uvp.sim.network.NetworkState
import com.uvp.sim.network.RemoteEndpoint
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.SipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.network.UdpSipTransport
import com.uvp.sim.sip.GbVersionNegotiation
import com.uvp.sim.sip.RportObservation
import com.uvp.sim.sip.SipOutboxImpl
import com.uvp.sim.sip.SipState
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
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
import kotlinx.coroutines.sync.withLock

/**
 * AppEngine — 装配根(P1.5 真下沉)。
 *
 * commonMain 持 Engine 实例 + transport 装配 + 5 Coord buildCoordinators + 8 holder buildHolders。
 * Engine 退化为「5 Coord 引用 + 路由 + bridge」,装配链全在 AppEngine。
 * Android/iOS ViewModel 退化成薄转发,不再持装配逻辑。
 *
 * 公开 API 签名保持(避让轨 3 PR-B AppActions 拆切口)。
 *
 * Wave 4 PR-PLATFORM-RUNTIME:
 *   - 多接一个 [runtime] 参数,媒体三件套(camera/audio/recording)经 runtime 装配,
 *     不再走 [resources] supplier — Activity 不再亲手 new media,装配下沉到平台层
 *   - 视频配置变更(SimConfig.video diff)走 [PlatformRuntime.applyVideoConfig],
 *     真重建逻辑挪到 PlatformRuntimeAndroid 内部
 */
class AppEngine(
    private val resources: PlatformResources,
    private val runtime: PlatformRuntime,
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
    // R2 #3:每次 connect() 会 launch 3 个桥接 collector(currentChannelName / currentBroadcast /
    // broadcastSpeakerOn),disconnect 不取消时会泄漏。
    private val bridgeJobs = mutableListOf<kotlinx.coroutines.Job>()
    private val bridgeJobsMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 媒体装配三件套 — 由 [runtime] 在 connect 时装配 + 缓存(P3-3 装配下沉)。
     * cameraCapture / audioCapture / recordingService 跨 connect cycle 复用同一实例,
     * Activity 重建不影响,跟旧 MainActivity companion 单例语义一致。
     */
    @Volatile private var cameraCapture: com.uvp.sim.camera.CameraCapture? = null
    @Volatile private var audioCapture: com.uvp.sim.camera.AudioCapture? = null
    @Volatile private var recordingService: com.uvp.sim.recording.RecordingService? = null
    /** OSD config supplier — 默认从内部维护,SipViewModel 注入跟随 SimConfig.osd + currentChannelName 派生流。 */
    @Volatile private var osdConfigFlowProvider: () -> kotlinx.coroutines.flow.StateFlow<com.uvp.sim.config.OsdConfig> =
        { kotlinx.coroutines.flow.MutableStateFlow(initialConfig.osd).asStateFlow() }

    /**
     * 设备侧虚拟存储卡(GB-2022 A.2.4.14 / A.2.6.16)—— **单一真源**。
     *
     * 透传给 `ManscdpRouterImpl` → `DeviceControlSubRouter`,由后者在应答时把「卡片 + 本次读数」
     * 一起写进 [holders] 的 `deviceControlState`,模拟中心的「存储卡」卡片再读它渲染。
     * ⛔ 两处各自 `new` 会出现"设备屏幕显示 2 张卡 32G、平台收到 1 张卡 8G" —— 见 [VirtualStorageCards]。
     *
     * 放在 AppEngine(而非 Engine)的理由同 [holders]:存储卡是**设备物理事实**,
     * 不该随"改配置 → disconnect/connect"重建而换一批卡 —— 那会让设备屏幕上的容量
     * 在一次重连后毫无理由地变掉(而平台库里的旧值也还在,两边当场对不上)。
     */
    private val virtualStorageCards = VirtualStorageCards()

    /** holders 在 AppEngine own,Engine 通过构造接引用 — 公开 StateFlow 直读 holder,免去 9 个 collect bridge。 */
    private val holders: EngineHolders = EngineHolders(
        state = MutableStateFlow(SipState.Disconnected),
        events = MutableSharedFlow(extraBufferCapacity = 64),
        deviceControlState = MutableStateFlow(DeviceControlModel()),
        catalogTree = MutableStateFlow(CatalogTreeStore.effectiveTree(initialConfig)),
        clockOffset = MutableStateFlow(ClockOffset.Empty),
        platformVersion = MutableStateFlow<GbVersion?>(null),
        rportObservation = MutableStateFlow<RportObservation?>(null),
        reconnect = MutableStateFlow<ReconnectAttempt?>(null),
        alarmHistoryStore = AlarmHistoryStore(),
        subscriptionRegistry = SubscriptionRegistry(engineScope),
        mockGps = runtime.buildLocationProvider(initialConfig.mockPosition),
        identityService = newDefaultIdentityService(localIpProvider = resources.localIpProvider),
    )

    /**
     * 设备状态的节流落盘器(2026-09-17)。
     *
     * 挂在 holders 上而不是 [SimulatorEngine] 上:engine 会随"改配置 → disconnect/connect"
     * 反复重建,而设备状态**不该**跟着重置 —— 它属于 AppEngine 的生命周期。
     */
    private val deviceStatePersister = DeviceStatePersister(
        source = holders.deviceControlState,
        store = resources.deviceStateStore,
    )

    /** 冷启动是否已尝试过恢复(含"读了但没存档"的情况)—— 幂等闩,见 [restoreDeviceState]。 */
    @Volatile private var deviceStateRestoreAttempted = false

    init {
        ProtocolClock.install {
            kotlin.time.Instant.fromEpochMilliseconds(holders.clockOffset.value.adjustedNowMs())
        }
        deviceStatePersister.start(engineScope)

        // 画面遮挡（GB-2022 A.2.1.17）要烧进**真实视频流**，而不是只画在 Compose 画布上 ——
        // 模拟器推给平台的是手机真实摄像头画面，「模拟中心」那块 3D 画布是另一条链路，
        // 黑块画在那里等于平台一点都看不到遮挡。
        //
        // ⭐ 来源只有两个，都在本类里：平台下发的 `deviceConfigs.pictureMask`（状态）+
        // `config.video.resolution`（**协议参考帧** —— 平台就是按它换算 `Point` 像素的，
        // 而采集/FBO/编码尺寸也是从同一个字段派生的，所以这一份归一化同时对得上两者）。
        // 装成 lambda 由渲染端逐帧读，不建 collect 协程（少一条生命周期要管）。
        runtime.installVideoMaskSupplier {
            com.uvp.sim.osd.VideoMaskOverlay.of(
                holders.deviceControlState.value.deviceConfigs.pictureMask,
                _config.value.video.resolution.widthPx,
                _config.value.video.resolution.heightPx,
            )
        }

        // 画面翻转（GB-2022 A.2.1.23）同理：平台点「上下翻转」要真的翻**推给平台的那路画面**，
        // 而不是只翻「模拟中心」那块 3D 画布（画布与推流是两条互不相干的链路）。
        //
        // ⭐ 这里只取 `frameMirror?.value` 一个整数就交给渲染端 —— 码值 → (mirrorX, mirrorY)
        // 的换算收敛在 `FrameMirrorTransform.of` 一处，Android GL 与 iOS CoreImage 两侧
        // 都不许再写一份 `when`（各写一份正是"改一处、另一平台静默不一致"的来源）。
        // ⛔ 不在这里把 null 兜成 0：`of()` 对 null 与 0 都返回"不翻"（画面表现相同），
        // 而"平台没配过 / 配了不启用"这两态的区分由展示层读 null 判断 —— 不靠这里丢信息。
        runtime.installFrameMirrorSupplier {
            com.uvp.sim.osd.FrameMirrorTransform.of(
                holders.deviceControlState.value.deviceConfigs.frameMirror?.value,
            )
        }
    }

    val state: StateFlow<SipState> = holders.state.asStateFlow()
    val events: SharedFlow<SimEvent> = holders.events.asSharedFlow()
    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> = holders.subscriptionRegistry.subscriptions
    /**
     * Wave 4 PR-UI-PROTOCOL-FIX:UI 直接订阅 [DeviceControlModel](业务模型),
     * 渲染层语义字段由 UI Mapper 调 `deriveRenderState` 派生。
     */
    val deviceControlState: StateFlow<DeviceControlModel> = holders.deviceControlState.asStateFlow()
    val catalogTree: StateFlow<List<CatalogNode>> = holders.catalogTree.asStateFlow()
    val alarmHistory: StateFlow<List<AlarmRecord>> = holders.alarmHistoryStore.history
    val clockOffset: StateFlow<ClockOffset> = holders.clockOffset.asStateFlow()
    /**
     * 附录 I 协商结果:平台在注册响应里声明的协议版本(null = 未声明/不可识别)。
     * UI 在设备设置页把它与本机声明并排显示 —— 现场联调时一眼确认两侧是否真在协商。
     */
    val platformVersion: StateFlow<GbVersion?> = holders.platformVersion.asStateFlow()

    /**
     * 平台视角的我方端点 + 是否经地址转换(null = 平台未回填 Via 的 received/rport)。
     *
     * 现场价值:回答「我填的地址平台能连上吗」。判定为 NAT 时,设置页会提示改用 TCP ——
     * 否则这类故障表现为「注册成功但点播/云台/查询全部超时」,和防火墙丢包长得一样。
     */
    val rportObservation: StateFlow<RportObservation?> = holders.rportObservation.asStateFlow()

    /**
     * SIP 长连接自愈状态(GB/T 28181 §5.2;null = 未在重连)。
     *
     * 直接读 holder 而非 `engine?.reconnect`:**engine 会随配置变更重建**,
     * 从 engine 取会在重建那一瞬变成 null,横幅闪一下"未连接"再跳回来。
     */
    val reconnect: StateFlow<ReconnectAttempt?> = holders.reconnect.asStateFlow()

    private val _currentChannelName = MutableStateFlow(initialConfig.device.videoChannelName)
    val currentChannelName: StateFlow<String> = _currentChannelName.asStateFlow()

    private val _activeLiveStream = MutableStateFlow(false)
    val activeLiveStream: StateFlow<Boolean> = _activeLiveStream.asStateFlow()

    private val _currentBroadcast = MutableStateFlow<BroadcastDialog?>(null)
    val currentBroadcast: StateFlow<BroadcastDialog?> = _currentBroadcast.asStateFlow()

    private val _broadcastSpeakerOn = MutableStateFlow(true)
    val broadcastSpeakerOn: StateFlow<Boolean> = _broadcastSpeakerOn.asStateFlow()

    /**
     * 冷启动恢复设备侧运行状态 —— 预置位 / 巡航轨迹 / 看守位 / 姿态 / 辅助开关(2026-09-17)。
     *
     * **为什么必须存在**:没有它,App 进程一重启设备侧就是一台**全新设备**(这些状态全在内存里,
     * DataStore 只存 SIP 配置)。而平台侧完全看不出来 —— 库里轨迹还在、`enabled` 是 NULL、
     * 点「开始巡航」照样下发成功、操作记录也是 `sent`,模拟器画面却纹丝不动,因为 `0x88`
     * 引用的那些预置位在**设备侧**一个都不存在。看守位更直接:它硬依赖预置位,号不存在就
     * **完全不动作**。现场排障时这表现为「这个功能没做」,实际只是设备数据没了。
     *
     * **幂等**:多次调用只有第一次真读盘。engine 重建(改配置 → disconnect/connect)时
     * 内存里的状态一定比磁盘新,再读一次会把操作员刚做的现场操作回退掉。
     *
     * **时序**:调用方应在冷启动尽可能早的位置调(见 SipViewModel.init),理由见下面的
     * 姿态恢复段 —— 3D 场景持有自己的 pan/tilt 且单向回写 Model,恢复晚了会被场景回写覆盖。
     */
    suspend fun restoreDeviceState() {
        if (deviceStateRestoreAttempted) return
        deviceStateRestoreAttempted = true
        val snapshot = runCatching { resources.deviceStateStore.loadOnce() }
            .onFailure { e ->
                SystemLogger.emit(
                    LogLevel.Warning,
                    LogTag.Resource,
                    "设备状态存档读取失败,按全新设备启动: ${e::class.simpleName}: ${e.message}",
                )
            }
            .getOrNull()
        if (snapshot == null || !snapshot.hasContent) {
            SystemLogger.emit(LogLevel.Info, LogTag.Resource, "本机无设备状态存档,按全新设备启动")
            return
        }
        holders.deviceControlState.update { snapshot.restoreInto(it) }
        // 这条日志是现场排障的关键观测点:"设备侧到底有没有数据"以前只能靠反复点巡航试探,
        // 现在冷启动一看就知道。
        SystemLogger.emit(LogLevel.Info, LogTag.Resource, "设备状态已恢复:${snapshot.summary()}")

        // 姿态光写 Model 不够 —— 3D 场景(Android GlbSceneState / iOS Filament view)持有自己的
        // pan/tilt,只**单向**回写 Model,从不读它。所以这里推一个 LocalPoseGoto 让已经挂载的
        // 渲染端落位;渲染端若还没挂载,它 attach 时会自行按 Model 对齐(见 GlbSceneState.attach)。
        // 两条通路互补,缺一条就会在某个时序下变成"存档写了但画面没动,166ms 后被回写覆盖"。
        //
        // 用 LocalPoseGoto 而不是 PresetRecall:后者在 Android 走 easeToPose,而 easeToPose 有
        // "开机自检中直接 return"的守卫 —— 冷启动恰好就在自检窗口内,恢复会被静默丢掉。
        if (snapshot.panAngle != 0f || snapshot.tiltAngle != 0f || snapshot.zoomLevel != 1f) {
            val pose = PtzPose(snapshot.panAngle, snapshot.tiltAngle, snapshot.zoomLevel)
            holders.deviceControlState.update {
                it.copy(pendingEffect = DeviceEffect.LocalPoseGoto(pose))
            }
        }
    }

    /**
     * 连接平台。装配链:transport → buildHolders(已 lazy)→ buildCoordinators → Engine →
     * Coord<->Engine 反向 ref → snapshot pipeline → bridge(currentChannelName/currentBroadcast/broadcastSpeakerOn 3 个 invite/broadcast 专属)→ register。
     *
     * R3 #3 (full preset HIGH/architecture):原 72 行混合 5 个职责,拆成 4 个 helper:
     *   reuseExistingEngine → buildTransportSession → wireSessionAndBridges → startRegistration
     */
    suspend fun connect() {
        if (reuseExistingEngine()) return
        val cfg = _config.value
        val tx = buildTransportSession(cfg)
        val eng = wireSessionAndBridges(cfg, tx)
        startRegistration(tx, eng)
    }

    /** 已有 engine 时不重建,只补发 register/transport.connect。返回 true = 已处理无须新建。 */
    private suspend fun reuseExistingEngine(): Boolean {
        val existing = engine ?: return false
        when (holders.state.value) {
            SipState.Registering, SipState.Registered, SipState.InCall -> return true
            SipState.Disconnected, SipState.Failed -> {
                try {
                    transport?.connect()
                    existing.register()
                    return true
                } catch (e: Throwable) {
                    // cross-review R2 #3:reconnect 失败过去 catch 后仍返 true,外层
                    // connect() 不会重建 transport,引擎陷在死链上直到 app 重启。
                    // 现在显式清掉 stale engine / transport,返 false 让 connect() 走
                    // 重建路径(buildTransportSession + wireSessionAndBridges + startRegistration)。
                    holders.events.emit(com.uvp.sim.domain.transportErrorOf("register retry", e))
                    runCatching { transport?.close() }
                    transport = null
                    engine = null
                    return false
                }
            }
        }
    }

    /** 按 cfg.transport 建对应 SipTransport,写到 [transport] 字段并返回。 */
    private fun buildTransportSession(cfg: SimConfig): SipTransport {
        val tx: SipTransport = when (cfg.transport) {
            TransportType.TCP -> com.uvp.sim.network.TcpSipTransport(
                remote = RemoteEndpoint(
                    host = cfg.server.ip,
                    port = cfg.server.port,
                    transport = TransportType.TCP,
                    allowList = cfg.server.allowList,
                ),
                parentScope = engineScope,
            )
            TransportType.UDP -> UdpSipTransport(
                remote = RemoteEndpoint(
                    host = cfg.server.ip,
                    port = cfg.server.port,
                    transport = TransportType.UDP,
                    allowList = cfg.server.allowList,
                ),
                parentScope = engineScope,
            )
        }
        transport = tx
        return tx
    }

    /**
     * 装配 outbox / 媒体 / coordinators / SimulatorEngine / snapshot pipeline /
     * 3 个 invite/broadcast 专属 StateFlow 桥接。bridge job 全收到 [bridgeJobs] 防泄漏(R2 #3)。
     *
     * R3 verify-followup #4:engine 字段只在所有 wiring 成功后才发布,wiring 抛错时
     * cancel 已起的 bridge job + 清 snapshotHttp,避免半初始化的 engine 被下次 reuseExistingEngine 命中。
     */
    private suspend fun wireSessionAndBridges(cfg: SimConfig, tx: SipTransport): SimulatorEngine {
        val outbox = SipOutboxImpl(tx) { ev -> holders.events.emit(ev) }
        ensureMediaBuilt(cfg)
        var engineRef: SimulatorEngine? = null
        val coords = buildCoordinators(cfg, tx, outbox) { engineRef }
        val eng = SimulatorEngine(cfg, tx, engineScope, resources, coords, holders)
        engineRef = eng

        // 局部收 bridge job,wiring 任一步抛错统一回滚。成功后再合入 bridgeJobs。
        val localBridgeJobs = mutableListOf<kotlinx.coroutines.Job>()
        var localSnapshotHttp: HttpClient? = null
        try {
            val capture = resources.snapshotCapture
            val cache = resources.snapshotCache
            val httpEngine = resources.httpEngineFactory?.invoke()
            if (capture != null && cache != null && httpEngine != null) {
                val http = HttpClient(httpEngine)
                localSnapshotHttp = http
                eng.attachSnapshotPipeline(capture, cache, http)
                engineScope.launch { runCatching { cache.gc() } }
            }

            localBridgeJobs += engineScope.launch { eng.currentChannelName.collect { _currentChannelName.value = it } }
            localBridgeJobs += engineScope.launch { eng.activeLiveStream.collect { _activeLiveStream.value = it } }
            localBridgeJobs += engineScope.launch { eng.currentBroadcast.collect { _currentBroadcast.value = it } }
            localBridgeJobs += engineScope.launch { eng.broadcastSpeakerOn.collect { _broadcastSpeakerOn.value = it } }
        } catch (t: Throwable) {
            localBridgeJobs.forEach { it.cancel() }
            runCatching { localSnapshotHttp?.close() }
            throw t
        }

        // 所有 wiring 成功 → 公布 engine + 合并 bridge job
        snapshotHttp = localSnapshotHttp
        bridgeJobsMutex.withLock { bridgeJobs += localBridgeJobs }
        engine = eng
        return eng
    }

    /** transport 真连 + 发首条 REGISTER。失败 emit TransportError 不抛(沿用旧行为)。 */
    private suspend fun startRegistration(tx: SipTransport, eng: SimulatorEngine) {
        try {
            tx.connect()
            eng.register()
        } catch (e: Throwable) {
            holders.events.emit(com.uvp.sim.domain.transportErrorOf("connect", e))
        }
    }

    private fun buildCoordinators(
        cfg: SimConfig,
        tx: SipTransport,
        outbox: com.uvp.sim.sip.SipOutbox,
        engineRefProvider: () -> SimulatorEngine?,
    ): EngineCoordinators {
        val localPortProvider: () -> Int = { tx.localPort.takeIf { it > 0 } ?: 5060 }
        // P1-5: rtpFactory 透传 expectedClientHost — InviteCoord 在 TCP_PASSIVE 模式下
        // 传 SDP remote IP 作 accept guard;UDP/TCP_ACTIVE/PlaybackBuilder 传 null。
        val rtpFactory: ((String, Int, RtpMode, String?) -> com.uvp.sim.network.RtpSender)? =
            resources.rtpSenderFactory?.let { f ->
                { host, port, mode, expectedClientHost ->
                    f(host, port, engineScope, mode, expectedClientHost)
                }
            }
        // PlaybackBuilder 沿用 3 参签名(回放走 UDP,不需 accept guard)。
        val rtpFactoryForPlayback: ((String, Int, RtpMode) -> com.uvp.sim.network.RtpSender)? =
            rtpFactory?.let { f -> { host, port, mode -> f(host, port, mode, null) } }
        val playbackBuilder = resources.playbackBuilderFactory?.let { factory ->
            rtpFactoryForPlayback?.let { rtp -> factory(engineScope, cfg.recording.playbackAudioCodec, rtp) }
        }
        // Wave 2 PR-SN-IDENTITY:Manscdp 直走 identityService。
        // P2-3(2026-06-28):Reg/Broadcast/Invite/Playback 4 Coord 既有 6 lambda 入口
        // 改为本地装配段持有的共享 var 桥接,移除 registerPoolLambdasFrom 过渡桥
        // (避免"看起来从 identityService 派生 / 实际是独立 counter"的误导)。
        // Coord 签名不动,Wave 3+ 真迁后这段也可以一起删。
        val identityService = holders.identityService
        var poolCseq = 0
        var poolCallId: String? = null
        var poolFromTag: String? = null
        val cseqProvider: () -> Int = { poolCseq }
        val cseqIncrementer: () -> Int = { poolCseq += 1; poolCseq }
        val callIdProvider: () -> String? = { poolCallId }
        val callIdSetter: (String) -> Unit = { poolCallId = it }
        val fromTagProvider: () -> String? = { poolFromTag }
        val fromTagSetter: (String) -> Unit = { poolFromTag = it }

        val registration = RegistrationCoordinatorImpl(
            config = cfg, transport = tx, scope = engineScope, outbox = outbox,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            cseqProvider = cseqProvider, cseqIncrementer = cseqIncrementer,
            callIdProvider = callIdProvider, callIdSetter = callIdSetter,
            fromTagProvider = fromTagProvider, fromTagSetter = fromTagSetter,
        )
        val broadcast = BroadcastCoordinatorImpl(
            config = cfg, transport = tx, scope = engineScope, outbox = outbox,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            rtpReceiverFactory = resources.rtpReceiverFactory, audioSinkFactory = resources.audioSinkFactory,
            simEventEmit = { ev -> holders.events.emit(ev) },
            cseqProvider = cseqProvider, cseqIncrementer = cseqIncrementer,
            callIdProvider = callIdProvider, callIdSetter = callIdSetter,
            fromTagProvider = fromTagProvider, fromTagSetter = fromTagSetter,
        )
        val playback = PlaybackCoordinatorImpl(
            config = cfg, transport = tx, outbox = outbox, scope = engineScope,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            playbackBuilder = playbackBuilder, recordingService = recordingService ?: com.uvp.sim.recording.NoopRecordingService,
            simEventEmit = { ev -> holders.events.emit(ev) },
            cseqProvider = cseqProvider, cseqIncrementer = cseqIncrementer,
            callIdProvider = callIdProvider, callIdSetter = callIdSetter,
            fromTagProvider = fromTagProvider, fromTagSetter = fromTagSetter,
        )
        val invite = InviteCoordinatorImpl(
            config = cfg, transport = tx, outbox = outbox, scope = engineScope,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            cameraCapture = cameraCapture, audioCapture = audioCapture,
            rtpSenderFactory = rtpFactory,
            catalogTree = holders.catalogTree, clockOffsetProvider = { holders.clockOffset.value },
            mutableSipState = holders.state, simEventEmit = { ev -> holders.events.emit(ev) },
            cseqProvider = cseqProvider, cseqIncrementer = cseqIncrementer,
            callIdProvider = callIdProvider, callIdSetter = callIdSetter,
            fromTagProvider = fromTagProvider, fromTagSetter = fromTagSetter,
        )
        val manscdp = ManscdpRouterImpl(
            config = cfg, transport = tx, outbox = outbox, scope = engineScope,
            localIpProvider = resources.localIpProvider, localPortProvider = localPortProvider,
            subscriptionRegistry = holders.subscriptionRegistry,
            catalogTree = holders.catalogTree,
            alarmHistoryStore = holders.alarmHistoryStore,
            mutableDeviceControlState = holders.deviceControlState,
            rebootCallback = { engineRefProvider()?.rebootForDeviceControl() ?: Unit },
            requestKeyFrameCallback = { cameraCapture?.requestKeyFrame() },
            broadcastInvoker = broadcast,
            recordingService = recordingService ?: com.uvp.sim.recording.NoopRecordingService,
            mockGps = holders.mockGps,
            // 与 holders 里那份物理清单是**同一个实例** —— 否则卡片与报文会各掷各的骰子。
            virtualStorageCards = virtualStorageCards,
            clockOffsetProvider = { holders.clockOffset.value },
            // 附录 I:出站报文形态取 min(本机声明, 平台声明);平台未声明时退回本机版本。
            // 读的是 holders(而非构造期 cfg) —— 协商结论在注册响应到达时才产生。
            effectiveGbVersionProvider = {
                GbVersionNegotiation.effective(cfg.gbVersion, holders.platformVersion.value)
            },
            stateRegisteredOrInCall = { holders.state.value == SipState.Registered || holders.state.value == SipState.InCall },
            broadcastBusy = {
                // 双重 busy 判断:
                //   1. 已有一路 broadcast 正在跑(commonMain 状态机) — 防第二路重入
                //   2. 平台 gate — 预留给无法由媒体协调器化解的物理资源冲突
                val broadcastActive = broadcast.current.value != null
                val platformBusy = com.uvp.sim.media.BroadcastBusyGate.isBusy()
                if (broadcastActive || platformBusy) {
                    SystemLogger.emit(
                        LogLevel.Warning,
                        LogTag.Network,
                        "BROADCAST_BUSY current=$broadcastActive platform=$platformBusy " +
                            "reason=${com.uvp.sim.media.BroadcastBusyGate.busyReason() ?: "broadcast-active"}",
                    )
                }
                broadcastActive || platformBusy
            },
            simEventEmit = { ev -> holders.events.emit(ev) },
            identityService = identityService,
        )
        return EngineCoordinators(registration, broadcast, playback, invite, manscdp)
    }

    /**
     * 装媒体三件套(Wave 4 PR-PLATFORM-RUNTIME)。
     *
     * 首次 connect 时调一次 — runtime 内部按需复用进程级单例(Android Streamer / RecordingService)。
     * camera / audio 的 CaptureConfig 派生自当前 SimConfig.video,确保跟用户最新配置一致。
     *
     * Public:SipViewModel 启动期手动调一次(早于 connect),让 recordingService 可用,
     * UI 进入"未连接但要试录像"路径不挂。
     */
    fun ensureMediaBuilt(cfg: SimConfig = _config.value) {
        val captureCfg = captureConfigOf(cfg)
        val audioCfg = audioCaptureConfigOf(cfg)
        if (cameraCapture == null) {
            cameraCapture = runtime.buildCameraCapture(captureCfg)
        }
        if (audioCapture == null) {
            audioCapture = runtime.buildAudioCapture(audioCfg)
        }
        if (recordingService == null) {
            recordingService = runtime.buildRecordingService(
                scope = engineScope,
                deviceIdSupplier = { _config.value.device.deviceId },
                encoderConfigSupplier = { recordingEncoderConfigOf(_config.value) },
                osdConfigSupplier = { osdConfigFlowProvider() },
                profileSupplier = { _config.value.recording },
            )
        }
    }

    /**
     * SipViewModel 启动期注入 OSD 配置 supplier(跟 SimConfig.osd + currentChannelName 派生流)。
     * 必须在 connect / videoConfig 变更前调,否则录像 / 推流 OSD 文字会用 default。
     */
    fun setOsdConfigFlowProvider(provider: () -> kotlinx.coroutines.flow.StateFlow<com.uvp.sim.config.OsdConfig>) {
        osdConfigFlowProvider = provider
    }

    /** 暴露当前 cameraCapture(SipViewModel 触发 startStream 等需要直接引用)。 */
    fun currentCameraCapture(): com.uvp.sim.camera.CameraCapture? = cameraCapture
    /** 暴露当前 recordingService(SipViewModel 录像 UI 直接调 start/stop/delete)。 */
    fun currentRecordingService(): com.uvp.sim.recording.RecordingService? = recordingService

    suspend fun cancelConnect() {
        val eng = engine
        try { eng?.cancelRegister() } catch (_: Throwable) {}
        try { eng?.shutdown() } catch (_: Throwable) {}
        // Engine shutdown 只会取消媒体 job；iOS 的 callbackFlow awaitClose
        // 可能异步执行。显式 stop 保证 AVAudioEngine 和 BroadcastBusyGate lease
        // 在取消连接/重注册时立即释放，即使 engine 已经被清空。
        try { audioCapture?.stop() } catch (_: Throwable) {}
        try { transport?.close() } catch (_: Throwable) {}
        try { snapshotHttp?.close() } catch (_: Throwable) {}
        bridgeJobsMutex.withLock { bridgeJobs.forEach { it.cancel() }; bridgeJobs.clear() }
        engine = null
        transport = null
        snapshotHttp = null
        _activeLiveStream.value = false
        // 重连标记必须跟 engine 一起清:它由 engine 的监管器写,engine 没了就没人再改它,
        // 留着会让横幅一直显示"正在重连(第 N 次)"而实际什么都没在跑。
        holders.reconnect.value = null
    }

    suspend fun disconnect() {
        val eng = engine
        try { eng?.unregister() } catch (_: Throwable) {}
        try { eng?.shutdown() } catch (_: Throwable) {}
        // unregister 会停止正常的 INVITE 流，但仍补一次 facade stop，覆盖
        // engine 在注册中途失败或 callbackFlow 尚未完成 awaitClose 的情况。
        try { audioCapture?.stop() } catch (_: Throwable) {}
        try { transport?.close() } catch (_: Throwable) {}
        try { snapshotHttp?.close() } catch (_: Throwable) {}
        bridgeJobsMutex.withLock { bridgeJobs.forEach { it.cancel() }; bridgeJobs.clear() }
        engine = null
        transport = null
        snapshotHttp = null
        _activeLiveStream.value = false
        holders.reconnect.value = null
    }

    fun onAppBackground() {
        engine?.onAppBackground()
        runtime.onAppBackground()
        // 切后台是"进程随时可能被系统回收"的前一刻,按节拍等下一次结算就太晚了 —— 显式落一次。
        engineScope.launch { deviceStatePersister.flush() }
    }

    fun onAppForeground() {
        runtime.onAppForeground()
        engine?.onAppForeground()
    }

    /**
     * 替换 SimConfig + 持久化。已连接时自动 disconnect → connect cycle。
     *
     * Bug 修复(PR-USER-BUG-1):rehydrate holders 让 catalogTree / mockGps /
     * currentChannelName / clockOffset / subscriptionRegistry 这些"派生自 SimConfig
     * 或承载运行期状态"的 holder 立刻反映新配置 — 否则即便 SimConfig 切了,
     * 单例 holders 还沿用旧值,用户改 deviceId / videoChannelId 后会看到旧 catalog tree、
     * 旧通道名等假象。
     *
     * Wave 4 PR-PLATFORM-RUNTIME:video 子配置变更 → runtime.applyVideoConfig 接管真重建。
     */
    suspend fun updateConfig(new: SimConfig) {
        val prev = _config.value
        _config.value = new
        rehydrateHolders(new)
        if (prev.video != new.video || prev.audioTransport != new.audioTransport) {
            // cross-review R2 #3:runCatching 吞错会让 streamer/audio 重建失败时
            // 持久化 config 已变,但 pipeline 还跑旧的;UI 没任何信号 → 用户面对静默
            // 偏差。这里把失败上报到 events(已被 ViewModel toast),保持运行期 fail-safe
            // 但显式可见。
            runCatching {
                runtime.applyVideoConfig(captureConfigOf(new), audioCaptureConfigOf(new))
            }.onFailure { e ->
                holders.events.emit(com.uvp.sim.domain.transportErrorOf("应用视频配置", e))
            }
        }
        // cross-review R1 #3:持久化失败不能静默吞 —— 过去 runCatching 丢弃 save 异常,
        // keystore/DataStore 写失败时引擎继续用未落盘的 config 跑,冷启动后改动丢失且用户无感知。
        // 现在把失败上报为 TransportError,UI 能提示;运行期仍沿用新 config(用户可见即所得),
        // 但至少不再假装持久化成功。
        runCatching { resources.configStore.save(new) }
            .onFailure { e ->
                holders.events.emit(com.uvp.sim.domain.transportErrorOf("保存配置", e))
            }
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

    /**
     * cross-review R1 #3 修复 — 由平台壳(Android MainActivity onRequestPermissionsResult /
     * iOS AppDelegate)在用户授予 LOCATION 权限后调用,让已存在的 MobilePosition 订阅立即恢复
     * 定位流。engine 未起时 no-op(权限授予早于 connect,下次 connect 走正常路径)。
     */
    suspend fun onLocationPermissionGranted() { engine?.resyncLocationLifecycle() }
    suspend fun stopStream(reason: String = "user stop") { engine?.stopStream(reason) }
    suspend fun stopBroadcast(reason: BroadcastEndReason = BroadcastEndReason.Local) { engine?.stopBroadcast(reason) }
    fun setBroadcastSpeaker(on: Boolean) {
        engine?.setBroadcastSpeaker(on) ?: run { _broadcastSpeakerOn.value = on }
    }
    suspend fun updateCatalogTree(tree: List<CatalogNode>) {
        holders.catalogTree.value = tree
        engine?.updateCatalogTree(tree)
    }

    /**
     * 用户在「设置 → OSD 水印 → GB/T 28181 前端 OSD」手动配置后回写。
     *
     * ★ `state = null` 的语义是**清除平台值、回到出厂派生**：用户在本地三层 OSD 那边改了配置时，
     * 设备当前的 OSD 就应当由本地方案决定。不这样做的话，平台会一直读到"自己配过的那个旧值"，
     * 而设备屏幕上显示的已经是本地新值 —— 两边对不上，且**看起来都正常**。
     *
     * ⛔ 走本族唯一的写入口 [withDeviceConfig]，但**不碰 `pendingEffect` / `lastCommand`** ——
     * 那两个字段是"平台改了配置"给操作员的提示，本地自己改再弹一次会被误认成平台下发。
     * 落盘交给挂在同一份状态上的 `DeviceStatePersister`（节流），这里不要手动存。
     */
    fun updateFrontOsd(state: FrontOsdState?) {
        holders.deviceControlState.update { model ->
            model.withDeviceConfig { it.copy(frontOsd = state) }
        }
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

    fun adjustLocalPtzPosition(panDelta: Float, tiltDelta: Float, zoomDelta: Float) {
        engine?.adjustLocalPtzPosition(panDelta, tiltDelta, zoomDelta)
    }

    /** 本机推光圈/聚焦(FI 族)。见 [SimulatorEngine.adjustLocalLensPosition]。 */
    fun adjustLocalLensPosition(focusDelta: Float, irisDelta: Float) {
        engine?.adjustLocalLensPosition(focusDelta, irisDelta)
    }

    /** ViewModel 用来更新 SimConfig 的 in-memory 视图(外部 save 后调,避免重复持久化)。
     *
     * Bug 修复(PR-USER-BUG-1):同步走 [rehydrateHolders] 重派生 catalogTree / mockGps /
     * currentChannelName / clockOffset / subscriptionRegistry 这些"派生自 SimConfig 或
     * 跟随旧配置生命周期的"holder。否则单例 holder 沿用旧值,用户改了 deviceId、
     * videoChannelName、mockPosition 等会看到旧值假象。
     *
     * Wave 4:video 配置 diff → runtime.applyVideoConfig(captureConfig, audioConfig),
     * AndroidStreamer 真重建,recordingService 已通过 supplier 自动拿新 encoderConfig。
     */
    fun setConfig(new: SimConfig) {
        val prev = _config.value
        _config.value = new
        rehydrateHolders(new)
        if (prev.video != new.video || prev.audioTransport != new.audioTransport) {
            // cross-review R2 #3:跟 [updateConfig] 同款根因 —— runCatching 吞错让
            // streamer 重建失败时 pipeline 跑旧的、_config 已是新的,UI 无信号。
            // setConfig 是同步 fun(ViewModel 已 save 后调),用 tryEmit 把失败丢到
            // events buffer(extraBufferCapacity=64),不阻塞。
            runCatching {
                runtime.applyVideoConfig(captureConfigOf(new), audioCaptureConfigOf(new))
            }.onFailure { e ->
                holders.events.tryEmit(com.uvp.sim.domain.transportErrorOf("应用视频配置", e))
            }
        }
    }

    /**
     * P1-1(2026-06-28)局部应用 — 只更新 `_config` 与从 SimConfig 派生的 [EngineHolders.catalogTree],
     * 不触碰运行期状态(clockOffset / subscriptionRegistry / mockGps / currentChannelName)。
     *
     * 用于"保存目录树 / OSD / 水印 / mockPosition 局部字段"这类只换数据快照、不需要重启会话的场景。
     * 调用方应自行确认改动字段不涉及 device / server / video / audioTransport — 那些走 [setConfig] /
     * [updateConfig] 才能拿到正确的全量 rehydrate + runtime.applyVideoConfig 链路。
     */
    fun applyConfigPartial(new: SimConfig) {
        _config.value = new
        holders.catalogTree.value = CatalogTreeStore.effectiveTree(new)
    }

    /**
     * 把 holder 内部状态从 [new] SimConfig 重新派生 — 不替换 holder 实例引用,
     * 已绑给 Coord 的 holder 通过内部 setter / reset 同步刷新。
     *
     * - catalogTree:重算 effectiveTree(new) 写入
     * - mockGps:reset 起点 = new.mockPosition
     * - currentChannelName:回写 new.device.videoChannelName(无 engine 状态时直接写;
     *   有 engine 时下一轮 connect 由 InviteCoordinator 接管)
     * - clockOffset:清空(旧 Date 头基准跟旧 server 绑定,换 server 后必须重新校时)
     * - subscriptionRegistry:cancelAll(旧订阅 callId 由旧 server / deviceId 路由,无效)
     */
    private fun rehydrateHolders(new: SimConfig) {
        holders.catalogTree.value = CatalogTreeStore.effectiveTree(new)
        // MockGpsSource 是 LocationProvider 的测试实现,生产 impl(Android LocationManager)
        // 无 reset 语义 — cast 保证只在测试 / iOS stub 场景生效,生产端 no-op
        (holders.mockGps as? MockGpsSource)?.reset(new.mockPosition)
        _currentChannelName.value = new.device.videoChannelName
        holders.clockOffset.value = ClockOffset.Empty
        holders.subscriptionRegistry.cancelAll()
    }

    /**
     * 测试可见 — Engine 实例(单测验证内部 state)。
     * 生产代码不应依赖。
     */
    internal fun engineForTest(): SimulatorEngine? = engine

    /** 测试可见 — mockGps 实例(单测验证 rehydrate 后起点)。 */
    internal fun mockGpsForTest(): MockGpsSource = holders.mockGps as MockGpsSource

    /** 测试可见 — subscriptionRegistry 实例(单测验证 rehydrate 触发 cancelAll)。 */
    internal fun subscriptionRegistryForTest(): SubscriptionRegistry = holders.subscriptionRegistry

    // ---- Wave 4 PR-PLATFORM-RUNTIME helpers ----

    /** SimConfig.video → CameraCapture CaptureConfig 派生。 */
    private fun captureConfigOf(cfg: SimConfig): com.uvp.sim.camera.CaptureConfig {
        val v = cfg.video
        return com.uvp.sim.camera.CaptureConfig(
            widthPx = v.resolution.widthPx,
            heightPx = v.resolution.heightPx,
            frameRate = v.frameRate,
            bitrateBps = v.bitrateKbps * 1000,
            keyframeIntervalSeconds = v.keyframeIntervalSeconds,
            videoCodec = v.videoCodec,
        )
    }

    private fun audioCaptureConfigOf(cfg: SimConfig): com.uvp.sim.camera.AudioCaptureConfig {
        val v = cfg.video
        return com.uvp.sim.camera.AudioCaptureConfig(
            codec = v.audioCodec,
            sampleRateHz = v.effectiveAudioSampleRateHz,
        )
    }

    private fun recordingEncoderConfigOf(cfg: SimConfig): RecordingEncoderConfig {
        val v = cfg.video
        return RecordingEncoderConfig(
            widthPx = v.resolution.widthPx,
            heightPx = v.resolution.heightPx,
            frameRate = v.frameRate,
            bitrateBps = v.bitrateKbps * 1000,
            keyframeIntervalSeconds = v.keyframeIntervalSeconds,
            audioCodec = v.audioCodec,
            videoCodec = v.videoCodec,
        )
    }

    /**
     * 注入自定义 RecordingService(绕过 runtime),用于测试替身或外部托管。
     * Wave 4 PR-PLATFORM-RUNTIME 后,生产路径走 [ensureMediaBuilt] 让 runtime 装配,
     * 这个入口仅留给 [com.uvp.sim.SipViewModel.bindRecordingService] 测试 seam 转发。
     */
    fun bindRecordingService(svc: com.uvp.sim.recording.RecordingService) {
        recordingService = svc
    }

    /** 测试可见 — 注入自定义媒体引用(绕过 runtime),用于 RecordingFlowTest 等场景。 */
    internal fun bindCameraCaptureForTest(cam: com.uvp.sim.camera.CameraCapture) {
        cameraCapture = cam
    }
    internal fun bindAudioCaptureForTest(audio: com.uvp.sim.camera.AudioCapture) {
        audioCapture = audio
    }
}

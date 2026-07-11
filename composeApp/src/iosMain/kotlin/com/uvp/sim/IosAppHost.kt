package com.uvp.sim

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.uvp.sim.app.AppEngine
import com.uvp.sim.app.PlatformResourcesIos
import com.uvp.sim.app.PlatformRuntimeIos
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.AlarmPriority
import com.uvp.sim.network.NetworkController
import com.uvp.sim.network.NetworkState
import com.uvp.sim.network.TransportType
import com.uvp.sim.observability.SystemLog
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.api.LogTag
import com.uvp.sim.camera.IosCameraController
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.recording.RecordingState
import com.uvp.sim.ui.AlarmFireMode
import com.uvp.sim.ui.App
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.BroadcastState
import com.uvp.sim.ui.RecordingStatus
import com.uvp.sim.ui.SubscriptionKind
import com.uvp.sim.ui.SubscriptionStatus
import com.uvp.sim.ui.actions.CapabilityActions
import com.uvp.sim.ui.actions.HomeActions
import com.uvp.sim.ui.actions.NetworkActions
import com.uvp.sim.ui.actions.RecordingActions
import com.uvp.sim.ui.model.NetworkStateDto
import com.uvp.sim.ui.model.mapper.toDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.time.Clock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

/**
 * iOS UI host — wires AppEngine into Compose App().
 *
 * v1.1 status: minimal wiring. Shows the real 3-tab UI (Home / Capability /
 * Recording). Media (camera / audio / recording) are stubs from
 * [PlatformRuntimeIos] — real AVCaptureSession / AVAudioEngine wiring lands
 * in T4-follow-up / T8-follow-up. Some flows (subscriptions / recording
 * files) are not yet mapped from AppEngine snapshots to their UI DTOs on
 * iOS — those UI sections show empty state until later PRs.
 */
object IosAppHost {

    private val hostScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var diagnosticsHeartbeatJob: Job? = null
    @Volatile private var mainThreadWatchdogJob: Job? = null
    @Volatile private var lastMainThreadAckMs: Long = -1L
    @Volatile private var lastMainThreadStallLogMs: Long = -1L

    /**
     * 诊断计数:IosApp 根 composable 的重组次数(SideEffect 每次成功重组 +1)。
     * 心跳读它的 delta 判断主线程是被"重组风暴"占死(暴涨)还是"阻塞调用"卡死(不动)。
     */
    @Volatile var recomposeCount: Long = 0
        private set

    internal fun incRecompose() { recomposeCount += 1 }

    /** NetworkController (Wave 1 A4, NWPathMonitor)。IosAppHost 起时装,close 由进程退出兜底。 */
    val networkController: NetworkController = NetworkController()

    private val engine: AppEngine by lazy {
        AppEngine(
            resources = PlatformResourcesIos(),
            runtime = PlatformRuntimeIos(),
            initialConfig = defaultConfig(),
            parentScope = hostScope,
        )
    }

    // 首次启动默认值:通道 ID 硬编码给合法 GB28181 编码
    // (domain 3402000000 = 浙江/社会管理;132 视频通道 / 134 报警通道)
    // 平台侧信息(IP/port/serverId/password)仍留空,强制用户按实际平台填。
    fun defaultConfig() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "",
            port = 0,
            serverId = "",
            domain = "3402000000"
        ),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001320000010",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "",
            frontChannelId = "34020000001320000020",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
    )

    fun bindLogger() {
        SystemLogger.bindScope(hostScope)
        SystemLogger.setMirrorSink { log ->
            println(
                "[${log.seq}] [${log.level.short}] [${log.tag.display}] ${log.message}" +
                    (log.detail?.let { "\n$it" } ?: "")
            )
        }
        startDiagnosticsHeartbeat()
        startMainThreadWatchdog()
    }

    private fun startDiagnosticsHeartbeat() {
        diagnosticsHeartbeatJob?.cancel()
        diagnosticsHeartbeatJob = hostScope.launch {
            var prevEmitCount = SystemLogger.emitCount
            var prevRecomposeCount = recomposeCount
            var prevTickMs = Clock.System.now().toEpochMilliseconds()
            while (isActive) {
                delay(30_000L)
                val nowTickMs = Clock.System.now().toEpochMilliseconds()
                val elapsedSec = ((nowTickMs - prevTickMs).coerceAtLeast(1L)) / 1000.0
                val curEmit = SystemLogger.emitCount
                val curRecompose = recomposeCount
                val emitRate = ((curEmit - prevEmitCount) / elapsedSec).toLong()
                val recomposeRate = ((curRecompose - prevRecomposeCount) / elapsedSec).toLong()
                prevEmitCount = curEmit
                prevRecomposeCount = curRecompose
                prevTickMs = nowTickMs
                val svc = engine.currentRecordingService()
                SystemLogger.emit(
                    com.uvp.sim.observability.LogLevel.Debug,
                    LogTag.Media,
                    buildString {
                        append("IOS_APP_HEARTBEAT ")
                        append("sip=").append(engine.state.value)
                        append(" recSvc=").append(svc?.state?.value ?: "null")
                        append(" files=").append(svc?.files?.value?.size ?: 0)
                        append(" cameraSession=").append(IosCameraController.session.value != null)
                        append(" encodingActive=").append(IosCameraController.encodingActive.value)
                        append(" cameraLastSampleMs=").append(IosCameraController.lastSampleAtMs())
                        append(" cameraSampleCount=").append(IosCameraController.sampleCount())
                        append(" recLastFeedMs=").append((svc as? com.uvp.sim.recording.IosRecordingService)?.lastVideoFeedAtMs() ?: -1L)
                        append(" recLastAppendMs=").append((svc as? com.uvp.sim.recording.IosRecordingService)?.lastVideoAppendAtMs() ?: -1L)
                        append(" memMb=").append(IosMemoryProbe.physFootprintMb())
                        append(" emitRate=").append(emitRate).append("/s")
                        append(" recomposeRate=").append(recomposeRate).append("/s")
                        append(" logs=").append(SystemLogger.snapshot.size)
                    }
                )
            }
        }
    }

    private fun startMainThreadWatchdog() {
        mainThreadWatchdogJob?.cancel()
        mainThreadWatchdogJob = hostScope.launch {
            while (isActive) {
                val probeSentAt = Clock.System.now().toEpochMilliseconds()
                dispatch_async(dispatch_get_main_queue()) {
                    lastMainThreadAckMs = Clock.System.now().toEpochMilliseconds()
                }
                delay(4_000L)
                val now = Clock.System.now().toEpochMilliseconds()
                val ack = lastMainThreadAckMs
                val ackLagMs = if (ack > 0L) now - ack else Long.MAX_VALUE
                if (ack < probeSentAt && now - lastMainThreadStallLogMs >= 10_000L) {
                    lastMainThreadStallLogMs = now
                    SystemLogger.emit(
                        com.uvp.sim.observability.LogLevel.Warning,
                        LogTag.Media,
                        "IOS_MAIN_THREAD_STALL probeSentAt=$probeSentAt lastAckMs=$ack ackLagMs=$ackLagMs " +
                            "memMb=${IosMemoryProbe.physFootprintMb()}"
                    )
                }
                delay(1_000L)
            }
        }
    }

    /** T-E4-1:iOS 前后台切换 → 停 broadcast + deactivate audio session。幂等。 */
    private val broadcastLifecycle: BroadcastLifecycleObserver by lazy {
        BroadcastLifecycleObserver(engine = engine, scope = hostScope)
    }

    fun attachBroadcastLifecycleObserver() {
        broadcastLifecycle.attach()
    }

    val appEngine: AppEngine get() = engine
    val scope: CoroutineScope get() = hostScope
}

@Composable
fun IosApp() {
    // 诊断:每次根 composable 重组 +1,心跳读 delta 判断是否重组风暴。
    androidx.compose.runtime.SideEffect { IosAppHost.incRecompose() }

    val engine = IosAppHost.appEngine
    val scope = IosAppHost.scope

    LaunchedEffect(Unit) {
        IosAppHost.bindLogger()
        // T-E4-1:注册前后台切换观察者 —— 切后台自动停 broadcast + deactivate audio session
        IosAppHost.attachBroadcastLifecycleObserver()
    }

    val networkController = IosAppHost.networkController

    val sipState by engine.state.collectAsState()
    val config by engine.config.collectAsState()
    val deviceControl by engine.deviceControlState.collectAsState()
    val catalogTree by engine.catalogTree.collectAsState()
    val alarmHistory by engine.alarmHistory.collectAsState()
    val clockOffset by engine.clockOffset.collectAsState()
    val rawSubs by engine.subscriptions.collectAsState()
    val currentBroadcast by engine.currentBroadcast.collectAsState()
    val speakerOn by engine.broadcastSpeakerOn.collectAsState()
    val networkState by networkController.state.collectAsState()

    var events by remember { mutableStateOf<List<SimEvent>>(emptyList()) }
    var systemLogs by remember { mutableStateOf<List<SystemLog>>(emptyList()) }
    var alarmFireMode by remember { mutableStateOf(AlarmFireMode.Random) }
    var fixedAlarmTemplate by remember { mutableStateOf<AlarmPayload?>(null) }
    var recordingState by remember { mutableStateOf<RecordingState>(RecordingState.Idle) }
    var recordingFiles by remember { mutableStateOf<List<RecordingFile>>(emptyList()) }

    // 冷启动:load persisted config → setConfig → apply network preference。参考 Android SipViewModel。
    LaunchedEffect(Unit) {
        val stored = engine.configStore.loadOnce(IosAppHost.defaultConfig())
        if (stored != engine.config.value) {
            engine.setConfig(stored)
        }
        networkController.apply(stored.network.preference)
    }

    // 2026-07-03 真机验:iOS 端 IosCameraStreamer.stream() 是 callbackFlow,只有 collector
    // 才会 wireCaptureSession → publish AVCaptureSession。不注册时无 collector,导致预览白屏
    // 和录像 writer 空转(stop 时 markAsFinished 抛 NSInvalidArgumentException 崩溃)。
    // 通过 keepalive 让 Registered / InCall 状态下 session 常驻:preview 有画面 + 录像能拿
    // 真 sample + 推流 encoding session 有 AVCaptureSession sample 输入。
    //
    // 2026-07-09 真机验(WVP 点播 iOS bug 3/3):旧逻辑在 sipState InCall 时 stop keepalive,
    // 走 IosCameraController.stopPreview → releaseInternal → forceEncodingReset,把推流用的
    // VT encoding session + AVCaptureSession 一并杀掉,推流只吃到 4 帧就断了。修法:InCall 也
    // 保持 keepalive,让 preview session 陪着推流跑完;点播结束 sipState 回到 Registered 时
    // 依然是 start(幂等);Disconnected/Error 才 stop。
    LaunchedEffect(sipState) {
        val v = config.video
        val cc = com.uvp.sim.camera.CaptureConfig(
            widthPx = v.resolution.widthPx,
            heightPx = v.resolution.heightPx,
            frameRate = v.frameRate,
            bitrateBps = v.bitrateKbps * 1000,
            keyframeIntervalSeconds = v.keyframeIntervalSeconds,
            videoCodec = v.videoCodec,
        )
        val keepAlive = sipState == com.uvp.sim.sip.SipState.Registered ||
            sipState == com.uvp.sim.sip.SipState.InCall
        if (keepAlive) {
            com.uvp.sim.camera.CameraSessionKeepalive.start(cc)
        } else {
            com.uvp.sim.camera.CameraSessionKeepalive.stop()
        }
    }

    LaunchedEffect(engine) {
        engine.events.collect { ev ->
            events = (events + ev).takeLast(200)
        }
    }

    // SystemLogger.flow 是 SharedFlow(replay=0),iOS 上用本地 state 累积快照。
    // iOS 上系统日志有时会被 MADService / AVFoundation 自己刷得很密,
    // 这里改成增量追加,避免每条都整包 snapshot 重建引发额外 UI churn。
    LaunchedEffect(Unit) {
        systemLogs = SystemLogger.snapshot
        SystemLogger.flow.collect { log ->
            systemLogs = if (log.tag == LogTag.User && log.message == "日志已清除") {
                emptyList()
            } else {
                (systemLogs + log).takeLast(500)
            }
        }
    }

    // 录像状态跟文件列表。engine.currentRecordingService() iOS 上是 NoopRecordingService,
    // 但也是真 StateFlow(Idle / empty),v1.2 接 AVAssetWriter 时零改动 host。
    // ensureMediaBound 触发一次装配,让 currentRecordingService 非 null。
    LaunchedEffect(Unit) {
        engine.ensureMediaBuilt()
        val svc = engine.currentRecordingService() ?: return@LaunchedEffect
        launch { svc.state.collect { recordingState = it } }
        launch { svc.files.collect { recordingFiles = it } }
    }

    // 主线程心跳：用于区分“UI/Main 卡死”与“后台 scope 还活着”。
    // 如果未来再次出现“看起来整 app 卡住”，而 IOS_APP_HEARTBEAT 还在继续、
    // 但这条不再出现，就说明更偏主线程/Compose/UIRunLoop 挂住。
    LaunchedEffect(Unit) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            kotlinx.coroutines.delay(5_000L)
            SystemLogger.emit(
                com.uvp.sim.observability.LogLevel.Debug,
                LogTag.Media,
                "IOS_UI_MAIN_HEARTBEAT"
            )
        }
    }

    // RecordingStatus(UI 层)从 recordingState + recordingFiles 组装。
    // 参考 Android MainActivity L137-148。
    val recordingStatus = run {
        val rec = recordingState as? RecordingState.Recording
        val failed = recordingState as? RecordingState.Failed
        RecordingStatus(
            isRecording = rec != null,
            source = rec?.source?.toDto(),
            startMs = rec?.startMs,
            segmentIndex = rec?.segmentIndex ?: 0,
            lastError = failed?.reason,
            files = recordingFiles.map { it.toDto() },
        )
    }

    // rawSubs: Map<String, SubscriptionSnapshot> → Map<SubscriptionKind, SubscriptionStatus>。
    // 未知 key 忽略(容错未来 engine 侧新增 kind)。参考 Android MainActivity L124-134。
    val subscriptions = rawSubs.mapNotNull { (kind, snap) ->
        val key = try { SubscriptionKind.valueOf(kind) } catch (_: Exception) { null }
            ?: return@mapNotNull null
        key to SubscriptionStatus(
            active = snap.active,
            subscriber = snap.subscriber,
            expiresSeconds = snap.expiresSeconds,
            remainingSeconds = snap.remainingSeconds,
            notifyCount = snap.notifyCount,
        )
    }.toMap()

    // BroadcastDialog(engine 内部)→ BroadcastState(UI 层)。isReceiving 用 dialog 存在与否。
    val broadcastState = currentBroadcast.let { bd ->
        if (bd == null) {
            BroadcastState(speakerOn = speakerOn)
        } else {
            BroadcastState(
                isReceiving = true,
                sourceId = bd.sourceId,
                codec = bd.codec.name,
                localAudioPort = bd.localAudioPort,
                remoteAudioHost = bd.remoteAudioHost,
                remoteAudioPort = bd.remoteAudioPort,
                rxPackets = bd.rxPackets,
                rxBytes = bd.rxBytes,
                seqLost = bd.seqLost,
                decodeErrors = bd.decodeErrors,
                speakerOn = speakerOn,
            )
        }
    }

    val uiState = AppUiState(
        sip = sipState.toDto(),
        config = config,
        events = events.map { it.toDto() },
        systemEvents = systemLogs.map { it.toDto() },
        // sessionMarker: iOS v1.1 无 SessionTracker(Android-only),保留 null。
        subscriptions = subscriptions,
        deviceControl = deviceControl.toDto(),
        recording = recordingStatus,
        // playback: v1.1 iOS 无回放路径,保留默认 PlaybackStatus()。
        catalogTree = catalogTree,
        alarmHistory = alarmHistory.map { it.toDto() },
        alarmFireMode = alarmFireMode,
        fixedAlarmTemplate = fixedAlarmTemplate?.toDto(),
        broadcast = broadcastState,
        // Wave 1 A4 后 NetworkController.ios 走 NWPathMonitor,collect 真状态。
        // iOS 不支持强制绑网卡,localIp 留空但 preference 会跟着 UI 切换 apply。
        networkRuntimeState = networkState.toDto(),
        clockOffset = clockOffset.toDto(),
    )

    val actions = buildActions(
        engine = engine,
        scope = scope,
        onAlarmModeChange = { alarmFireMode = it },
        onFixedAlarmSave = { fixedAlarmTemplate = it },
        currentMode = { alarmFireMode },
        currentFixed = { fixedAlarmTemplate },
    )

    App(state = uiState, actions = actions)
}

private fun buildActions(
    engine: AppEngine,
    scope: CoroutineScope,
    onAlarmModeChange: (AlarmFireMode) -> Unit,
    onFixedAlarmSave: (AlarmPayload) -> Unit,
    currentMode: () -> AlarmFireMode,
    currentFixed: () -> AlarmPayload?,
): AppActions {
    val home = object : HomeActions {
        override fun onConnect() { scope.launch { engine.connect() } }
        override fun onCancelConnect() { scope.launch { engine.cancelConnect() } }
        override fun onDisconnect() { scope.launch { engine.disconnect() } }
        override fun onConfigSave(updated: SimConfig) { scope.launch { engine.updateConfig(updated) } }
        override fun onClearSipLogs() { /* v1.1: engine no clear-sip API; leave to later PR */ }
        override fun onClearSystemLogs() { SystemLogger.clear() }
        override fun onConsumeDeviceEffect() { engine.consumeEffect() }
    }
    val fallback = AlarmPayload(deviceId = engine.config.value.device.deviceId, priority = AlarmPriority.General)
    val capability = object : CapabilityActions {
        override fun onSnapshot() { scope.launch { engine.reportSnapshot() } }
        override fun onAlarmFire(payload: AlarmPayload) { scope.launch { engine.reportAlarm(payload) } }
        override fun onAlarmReset() { scope.launch { engine.localResetAlarm() } }
        override fun onAlarmFireDefault() {
            val payload = when (currentMode()) {
                AlarmFireMode.Fixed -> currentFixed() ?: fallback
                AlarmFireMode.Random -> fallback
            }
            scope.launch { engine.reportAlarm(payload) }
        }
        override fun onSetAlarmFireMode(mode: AlarmFireMode) { onAlarmModeChange(mode) }
        override fun onSaveFixedAlarm(payload: AlarmPayload) { onFixedAlarmSave(payload) }
        override fun onSimulateMediaStatusAbnormal(notifyType: Int) {
            scope.launch { engine.triggerMediaStatusAbnormal(notifyType) }
        }
        override fun onBroadcastStop() { scope.launch { engine.stopBroadcast() } }
        override fun onBroadcastToggleSpeaker(on: Boolean) { engine.setBroadcastSpeaker(on) }
        override fun onCatalogTreeSave(tree: List<CatalogNode>): String? {
            scope.launch { engine.updateCatalogTree(tree) }
            return null
        }
        override fun onToggleChannelStatus(channelId: String, online: Boolean) {
            scope.launch { engine.toggleChannelStatus(channelId, online) }
        }
        override fun onPoseTick(pan: Float, tilt: Float, zoom: Float) {
            engine.updatePoseFromRender(pan, tilt, zoom)
        }
    }
    val recording = object : RecordingActions {
        override fun onRecordingStart() {
            scope.launch {
                val svc = engine.currentRecordingService() ?: return@launch
                val source = com.uvp.sim.recording.RecordSource.Manual
                svc.start(source, engine.config.value.device.videoChannelId)
            }
        }
        override fun onRecordingStop() {
            scope.launch { engine.currentRecordingService()?.stop() }
        }
        override fun onRecordingDelete(id: String) {
            scope.launch { engine.currentRecordingService()?.delete(id) }
        }
        override fun onRecordingFilterApply(filter: RecordingFilter) {
            // Android 侧同样是纯 UI 本地筛选,不落 engine。
        }
    }
    val network = object : NetworkActions {
        override fun onNetworkPreferenceChange(preference: NetworkPreference) {
            // Wave 1 A4:NetworkController.ios 走 NWPathMonitor,apply 记录偏好并 emit 到 state。
            // iOS 无法强制绑网卡(系统限制),这里 apply 之后 state 会跟着 emit 反映活跃网卡的类型。
            scope.launch {
                IosAppHost.networkController.apply(preference)
                val cfg = engine.config.value
                engine.updateConfig(cfg.copy(network = cfg.network.copy(preference = preference)))
            }
        }
    }
    return object : AppActions,
        HomeActions by home,
        CapabilityActions by capability,
        RecordingActions by recording,
        NetworkActions by network {}
}

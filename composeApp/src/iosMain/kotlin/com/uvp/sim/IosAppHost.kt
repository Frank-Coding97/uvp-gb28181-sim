package com.uvp.sim

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.uvp.sim.network.TransportType
import com.uvp.sim.observability.SystemLog
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.ui.AlarmFireMode
import com.uvp.sim.ui.App
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.BroadcastState
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
import kotlinx.coroutines.launch

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

    private val engine: AppEngine by lazy {
        AppEngine(
            resources = PlatformResourcesIos(),
            runtime = PlatformRuntimeIos(),
            initialConfig = defaultConfig(),
            parentScope = hostScope,
        )
    }

    fun defaultConfig() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "",
            port = 0,
            serverId = "",
            domain = ""
        ),
        device = DeviceConfig(
            deviceId = "",
            videoChannelId = "",
            alarmChannelId = "",
            username = "",
            password = "",
            frontChannelId = "",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
    )

    fun bindLogger() {
        SystemLogger.bindScope(hostScope)
    }

    val appEngine: AppEngine get() = engine
    val scope: CoroutineScope get() = hostScope
}

@Composable
fun IosApp() {
    val engine = IosAppHost.appEngine
    val scope = IosAppHost.scope

    SideEffect {
        IosAppHost.bindLogger()
    }

    val sipState by engine.state.collectAsState()
    val config by engine.config.collectAsState()
    val deviceControl by engine.deviceControlState.collectAsState()
    val catalogTree by engine.catalogTree.collectAsState()
    val alarmHistory by engine.alarmHistory.collectAsState()
    val clockOffset by engine.clockOffset.collectAsState()
    val rawSubs by engine.subscriptions.collectAsState()
    val currentBroadcast by engine.currentBroadcast.collectAsState()
    val speakerOn by engine.broadcastSpeakerOn.collectAsState()

    var events by remember { mutableStateOf<List<SimEvent>>(emptyList()) }
    var systemLogs by remember { mutableStateOf<List<SystemLog>>(emptyList()) }
    var alarmFireMode by remember { mutableStateOf(AlarmFireMode.Random) }
    var fixedAlarmTemplate by remember { mutableStateOf<AlarmPayload?>(null) }

    LaunchedEffect(engine) {
        engine.events.collect { ev ->
            events = (events + ev).takeLast(200)
        }
    }

    // SystemLogger.flow 是 SharedFlow(replay=0),iOS 上用本地 state 累积快照。
    // 用 buffer.snapshot() 保持一次性对齐(重新订阅时补齐历史),后续增量走 flow。
    LaunchedEffect(Unit) {
        systemLogs = SystemLogger.snapshot
        SystemLogger.flow.collect { log ->
            systemLogs = (systemLogs + log).takeLast(500)
        }
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
        // recording / playback: engine 侧 iOS 走 NoopRecordingService,无对应 StateFlow,保留默认。
        catalogTree = catalogTree,
        alarmHistory = alarmHistory.map { it.toDto() },
        alarmFireMode = alarmFireMode,
        fixedAlarmTemplate = fixedAlarmTemplate?.toDto(),
        broadcast = broadcastState,
        // iOS NetworkController 是 no-op,spec 明确永远 Auto。
        networkRuntimeState = NetworkStateDto.Auto,
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
        override fun onRecordingStart() { /* v1.1: NoopRecordingService */ }
        override fun onRecordingStop() { /* v1.1: NoopRecordingService */ }
        override fun onRecordingDelete(id: String) { /* v1.1 */ }
        override fun onRecordingFilterApply(filter: RecordingFilter) { /* v1.1 */ }
    }
    val network = object : NetworkActions {
        override fun onNetworkPreferenceChange(preference: NetworkPreference) {
            // iOS NetworkController is no-op; only persist preference to config.
            val cfg = engine.config.value
            scope.launch { engine.updateConfig(cfg.copy(network = cfg.network.copy(preference = preference))) }
        }
    }
    return object : AppActions,
        HomeActions by home,
        CapabilityActions by capability,
        RecordingActions by recording,
        NetworkActions by network {}
}

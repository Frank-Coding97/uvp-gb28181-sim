package com.uvp.sim

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uvp.sim.app.PlatformResourcesAndroid
import com.uvp.sim.app.PlatformRuntimeAndroid
import com.uvp.sim.app.AppEngine
import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SubscriptionSnapshot
import com.uvp.sim.network.TransportType
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.recording.RecordingState
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Glues the cross-platform [AppEngine] to Android lifecycle.
 *
 * PR6 T6.4:装配根下沉到 [AppEngine](commonMain),ViewModel 退化为薄转发 + Android 平台特有 UI 状态包装。
 * 装配 / 连接 / 12 StateFlow 桥接全部由 AppEngine 处理。
 *
 * Wave 4 PR-PLATFORM-RUNTIME:
 *   - 不再持 camera / audio / recordingService 引用,媒体生命周期挪到 [PlatformRuntimeAndroid]
 *   - bindCamera / bindAudio / bindRecordingService 测试 seam 保留(仅用于单测注入 fake)
 *   - newCaptureConfig / newAudioCaptureConfig 保留,Activity 已不再用,留作向后兼容
 */
class SipViewModel(application: Application) : AndroidViewModel(application) {

    /** 平台运行时 — 媒体装配(camera/audio/recording)收口在这里。
     *
     * osdConfigSupplier 通过 [@Volatile] 中介变量,延迟到 osdConfig StateFlow 初始化之后再 wire。
     * 避免 runtime 字段初始化早于 osdConfig 时 lambda 直接读 osdConfig 抛 NPE。
     */
    @Volatile private var osdConfigCarrier: kotlinx.coroutines.flow.StateFlow<com.uvp.sim.config.OsdConfig>? = null

    private val runtime: PlatformRuntimeAndroid = PlatformRuntimeAndroid(
        context = application,
        osdConfigSupplier = { osdConfigCarrier },
    )

    private val resources: PlatformResourcesAndroid = PlatformResourcesAndroid(
        context = application,
        networkLocalIp = {
            (networkController.state.value as? com.uvp.sim.network.NetworkState.Bound)?.localIp
        },
    )

    private val appEngine: AppEngine = AppEngine(
        resources = resources,
        runtime = runtime,
        initialConfig = defaultConfig(),
        parentScope = viewModelScope,
    )

    // 委派给 AppEngine 的 StateFlow / SharedFlow
    val state: StateFlow<SipState> get() = appEngine.state
    val config: StateFlow<SimConfig> get() = appEngine.config
    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> get() = appEngine.subscriptions
    val deviceControl: StateFlow<DeviceControlModel> get() = appEngine.deviceControlState
    val catalogTree: StateFlow<List<CatalogNode>> get() = appEngine.catalogTree
    val alarmHistory: StateFlow<List<com.uvp.sim.domain.AlarmRecord>> get() = appEngine.alarmHistory
    val clockOffset: StateFlow<com.uvp.sim.domain.ClockOffset> get() = appEngine.clockOffset

    /** events 历史窗口(滚动列表,Android-only)。 */
    private val _events = MutableStateFlow<List<SimEvent>>(emptyList())
    val events: StateFlow<List<SimEvent>> = _events.asStateFlow()

    init {
        viewModelScope.launch {
            appEngine.events.collect { ev ->
                _events.update { current -> (listOf(ev) + current).take(MAX_EVENT_LOG) }
            }
        }
    }

    /**
     * OSD 视频叠加层配置 — 通道名 text 跟随 currentChannelName,Streamer 订阅。
     */
    val osdConfig: StateFlow<com.uvp.sim.config.OsdConfig> =
        combine(appEngine.config, appEngine.currentChannelName) { cfg, chName ->
            cfg.osd.copy(channelName = cfg.osd.channelName.copy(text = chName))
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            defaultConfig().osd.let { it.copy(channelName = it.channelName.copy(text = defaultConfig().device.videoChannelName)) }
        )

    init {
        // 把 osdConfig 派生流装到 runtime + AppEngine — 录像 / streamer 装配时拿到真实 supplier
        osdConfigCarrier = osdConfig
        appEngine.setOsdConfigFlowProvider { osdConfig }
    }

    init {
        // OSD 配置变更日志
        viewModelScope.launch {
            var prev: com.uvp.sim.config.OsdConfig? = null
            osdConfig.collect { now ->
                val before = prev
                if (before != null && before != now) {
                    val parts = mutableListOf<String>()
                    if (before.timestamp != now.timestamp) parts += "timestamp(${before.timestamp.enabled}→${now.timestamp.enabled})"
                    if (before.channelName != now.channelName) parts += "channelName(${before.channelName.enabled}→${now.channelName.enabled},text='${now.channelName.text}')"
                    if (before.watermark != now.watermark) parts += "watermark(${before.watermark.enabled}→${now.watermark.enabled},text='${now.watermark.text}')"
                    com.uvp.sim.observability.SystemLogger.emit(
                        com.uvp.sim.observability.LogLevel.Info,
                        com.uvp.sim.observability.LogTag.User,
                        "OSD_CONFIG_CHANGED",
                        detail = parts.joinToString(", ")
                    )
                }
                prev = now
            }
        }
    }

    /** 录像状态(Android-only)。 */
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordingFiles = MutableStateFlow<List<RecordingFile>>(emptyList())
    val recordingFiles: StateFlow<List<RecordingFile>> = _recordingFiles.asStateFlow()

    /** 一次性 toast。 */
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /** Video config 版本号 — Activity 重建 streamer。 */
    private val _videoConfigVersion = MutableStateFlow(0)
    val videoConfigVersion: StateFlow<Int> = _videoConfigVersion.asStateFlow()

    /** 最后一次成功保存目录树的时间。 */
    private val _lastCatalogSavedAt = MutableStateFlow<Long?>(null)
    val lastCatalogSavedAt: StateFlow<Long?> = _lastCatalogSavedAt.asStateFlow()

    /** 报警发送模式 + 固定单。 */
    private val _alarmFireMode = MutableStateFlow(com.uvp.sim.ui.AlarmFireMode.Random)
    val alarmFireMode: StateFlow<com.uvp.sim.ui.AlarmFireMode> = _alarmFireMode.asStateFlow()

    private val _fixedAlarm = MutableStateFlow<com.uvp.sim.gb28181.AlarmPayload?>(null)
    val fixedAlarm: StateFlow<com.uvp.sim.gb28181.AlarmPayload?> = _fixedAlarm.asStateFlow()

    /** 广播 UI 投影(BroadcastDialog + speakerOn 包装成 BroadcastState)。 */
    val broadcast: StateFlow<com.uvp.sim.ui.BroadcastState> =
        combine(appEngine.currentBroadcast, appEngine.broadcastSpeakerOn) { bc, speakerOn ->
            if (bc == null) com.uvp.sim.ui.BroadcastState()
            else com.uvp.sim.ui.BroadcastState(
                isReceiving = bc.state == com.uvp.sim.domain.BroadcastDialogState.Talking,
                sourceId = bc.sourceId,
                codec = bc.codec.name,
                localAudioPort = bc.localAudioPort,
                remoteAudioHost = bc.remoteAudioHost,
                remoteAudioPort = bc.remoteAudioPort,
                rxPackets = bc.rxPackets,
                rxBytes = bc.rxBytes,
                seqLost = bc.seqLost,
                decodeErrors = bc.decodeErrors,
                speakerOn = speakerOn,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, com.uvp.sim.ui.BroadcastState())

    /**
     * 网络选择控制器(Android-only)。
     */
    private val networkController = com.uvp.sim.network.NetworkController().apply {
        attach(application)
    }
    val networkState: StateFlow<com.uvp.sim.network.NetworkState> = networkController.state

    init {
        // Wave 4 PR-PLATFORM-RUNTIME:启动期装媒体三件套
        // 必须放在 _recordingState / _recordingFiles / _toasts 等 backing field 初始化之后,
        // 否则 wireRecordingService 触发 state collector emit → 写 _recordingState 时 NPE
        appEngine.ensureMediaBuilt()
        appEngine.currentRecordingService()?.let { svc -> wireRecordingService(svc) }
    }

    init {
        // 冷启动顺序(PR-USER-BUG-1 修复):
        // 1. AppEngine 构造时已用 defaultConfig() 装配 holders(loadOnce 等价)
        // 2. 灌入持久化 config → AppEngine.setConfig 触发 rehydrateHolders,
        //    catalogTree / mockGps / currentChannelName / clockOffset / subscriptionRegistry 跟随
        //    新 config 重派生 — 旧实现是直接 _config.value =,holder 不刷,
        //    用户改的 deviceId / videoChannelName / mockPosition 全被旧 default 盖死
        // 3. networkController.apply(stored.network.preference) — 应用持久化网络偏好
        // 4. 之后才启动 networkController.state.collect 推给 AppEngine
        viewModelScope.launch {
            val stored = migrateDualChannel(resources.configStore.loadOnce(defaultConfig()))
            if (stored != appEngine.config.value) {
                appEngine.setConfig(stored)
                _videoConfigVersion.value += 1
            }
            networkController.apply(stored.network.preference)
            launch {
                networkController.state.collect { netState ->
                    appEngine.handleNetworkChange(netState)
                }
            }
        }
    }

    /** UI 网络偏好切换(本地 config 同步 + AppEngine 重连)。 */
    fun applyNetworkPreference(preference: com.uvp.sim.config.NetworkPreference) {
        val cfg = appEngine.config.value
        val updated = cfg.copy(network = cfg.network.copy(preference = preference))
        updateConfig(updated)
        viewModelScope.launch { networkController.apply(preference) }
    }

    fun consumeDeviceEffect() = appEngine.consumeEffect()
    fun updatePoseFromRender(pan: Float, tilt: Float, zoom: Float) =
        appEngine.updatePoseFromRender(pan, tilt, zoom)

    /** 双真实通道迁移:老配置 frontChannelId 为空时按 domain 补全前置通道 ID。 */
    private fun migrateDualChannel(cfg: SimConfig): SimConfig {
        if (cfg.device.frontChannelId.isNotBlank()) return cfg
        if (cfg.server.domain.isBlank()) return cfg
        val frontId = com.uvp.sim.gb28181.IdEncoder.genChildId(
            cfg.server.domain, com.uvp.sim.config.CatalogNodeType.VideoChannel, 2
        )
        return cfg.copy(device = cfg.device.copy(frontChannelId = frontId))
    }

    /**
     * 测试 seam:注入 fake RecordingService 替换 AppEngine 内部引用,然后挂 collector。
     * Wave 4:生产路径已经由 AppEngine.ensureMediaBuilt() 在 init 时通过 runtime 装配 +
     * wireRecordingService 挂载;此处仅用于 RecordingFlowTest 之类替身注入。
     */
    fun bindRecordingService(svc: RecordingService) {
        appEngine.bindRecordingService(svc)
        wireRecordingService(svc)
    }

    /** 内部:把 RecordingService.state / files 挂到 ViewModel StateFlow。 */
    private fun wireRecordingService(svc: RecordingService) {
        viewModelScope.launch { runCatching { svc.load() } }
        viewModelScope.launch {
            var lastFailedReason: String? = null
            svc.state.collect { st ->
                _recordingState.value = st
                val reason = (st as? RecordingState.Failed)?.reason
                if (reason != null && reason != lastFailedReason) {
                    _toasts.tryEmit("录像失败:$reason")
                }
                lastFailedReason = reason
            }
        }
        viewModelScope.launch { svc.files.collect { _recordingFiles.value = it } }
    }

    fun startRecording() {
        val svc = appEngine.currentRecordingService() ?: return
        val cfg = appEngine.config.value
        viewModelScope.launch {
            runCatching { svc.start(RecordSource.Manual, cfg.device.videoChannelId) }
        }
    }

    fun stopRecording() {
        val svc = appEngine.currentRecordingService() ?: return
        viewModelScope.launch { runCatching { svc.stop() } }
    }

    fun deleteRecording(id: String) {
        val svc = appEngine.currentRecordingService() ?: return
        viewModelScope.launch {
            val result = runCatching { svc.delete(id) }
            if (result.isSuccess) _toasts.tryEmit("已删除")
            else _toasts.tryEmit("删除失败:${result.exceptionOrNull()?.message ?: "unknown"}")
        }
    }

    fun clearSipEvents() { _events.value = emptyList() }

    // ---- 全部薄转发 ----

    fun connect() = viewModelScope.launch { appEngine.connect() }
    fun disconnect() = viewModelScope.launch { appEngine.disconnect() }
    fun cancelConnect() = viewModelScope.launch { appEngine.cancelConnect() }

    fun updateConfig(newCfg: SimConfig) {
        val prev = appEngine.config.value
        val migrated = migrateDualChannel(newCfg)
        if (prev.video != migrated.video) {
            _videoConfigVersion.value += 1
        }
        viewModelScope.launch { appEngine.updateConfig(migrated) }
    }

    fun saveCatalogTree(tree: List<CatalogNode>): com.uvp.sim.domain.ValidationResult {
        val result = com.uvp.sim.domain.CatalogTreeStore.validate(tree)
        if (result is com.uvp.sim.domain.ValidationResult.Invalid) return result
        val newCfg = appEngine.config.value.copy(catalogTree = tree)
        _lastCatalogSavedAt.value = System.currentTimeMillis()
        viewModelScope.launch {
            appEngine.setConfig(newCfg)
            runCatching { resources.configStore.save(newCfg) }
            try {
                appEngine.updateCatalogTree(tree)
            } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(com.uvp.sim.domain.simTransportErrorOf("save catalog", e)) + current).take(MAX_EVENT_LOG)
                }
            }
        }
        return result
    }

    fun toggleChannelStatus(channelId: String, online: Boolean) {
        viewModelScope.launch {
            try {
                appEngine.toggleChannelStatus(channelId, online)
            } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(com.uvp.sim.domain.simTransportErrorOf("toggle channel status", e)) + current).take(MAX_EVENT_LOG)
                }
            }
        }
    }

    fun reportSnapshot() {
        viewModelScope.launch {
            try {
                appEngine.reportSnapshot()
            } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(com.uvp.sim.domain.simTransportErrorOf("snapshot", e)) + current).take(MAX_EVENT_LOG)
                }
            }
        }
    }

    fun fireAlarm(payload: com.uvp.sim.gb28181.AlarmPayload) {
        if (appEngine.state.value == SipState.Disconnected || appEngine.state.value == SipState.Failed) {
            _toasts.tryEmit("未注册,无法发送报警")
            return
        }
        viewModelScope.launch {
            try {
                appEngine.reportAlarm(payload)
            } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(com.uvp.sim.domain.simTransportErrorOf("alarm fire", e)) + current).take(MAX_EVENT_LOG)
                }
            }
        }
    }

    fun stopBroadcast() = viewModelScope.launch {
        runCatching { appEngine.stopBroadcast(com.uvp.sim.domain.BroadcastEndReason.Local) }
    }

    fun setBroadcastSpeaker(on: Boolean) = appEngine.setBroadcastSpeaker(on)

    fun resetAlarm() = viewModelScope.launch {
        runCatching { appEngine.localResetAlarm() }
    }

    fun fireAlarmDefault() {
        val cfg = appEngine.config.value
        val payload = when (_alarmFireMode.value) {
            com.uvp.sim.ui.AlarmFireMode.Fixed ->
                _fixedAlarm.value ?: com.uvp.sim.gb28181.AlarmTemplates.random().toPayload(cfg)
            com.uvp.sim.ui.AlarmFireMode.Random ->
                com.uvp.sim.gb28181.AlarmTemplates.random().toPayload(cfg)
        }
        fireAlarm(payload)
    }

    fun setAlarmFireMode(mode: com.uvp.sim.ui.AlarmFireMode) { _alarmFireMode.value = mode }

    fun saveFixedAlarm(payload: com.uvp.sim.gb28181.AlarmPayload) {
        _fixedAlarm.value = payload
        _alarmFireMode.value = com.uvp.sim.ui.AlarmFireMode.Fixed
    }

    fun simulateMediaStatusAbnormal(notifyType: Int) {
        if (appEngine.state.value == SipState.Disconnected || appEngine.state.value == SipState.Failed) {
            _toasts.tryEmit("未注册,无法发送 MediaStatus")
            return
        }
        viewModelScope.launch {
            try {
                appEngine.triggerMediaStatusAbnormal(notifyType)
            } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(com.uvp.sim.domain.simTransportErrorOf("MediaStatus simulate", e)) + current).take(MAX_EVENT_LOG)
                }
            }
        }
    }

    /**
     * Snapshot 管线由 AppEngine 内部 attach,Android 不需要单独调。
     * 保留方法签名以兼容老代码;实际是 no-op。
     */
    fun attachSnapshotPipeline() { /* no-op:AppEngine.connect 内部自动 attach */ }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 已销毁,没有更好的 scope。GlobalScope + 5s 超时兜底:
        // 允许后台清理 5 秒,超过则放弃避免泄漏。
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                runCatching {
                    appEngine.disconnect()
                    networkController.close()
                }
            }
        }
        // 注:runtime 单例(streamer / recordingService)跟 Activity 重建解耦,不在这里释放
    }

    fun newCaptureConfig(): CaptureConfig {
        val v = appEngine.config.value.video
        return CaptureConfig(
            widthPx = v.resolution.widthPx,
            heightPx = v.resolution.heightPx,
            frameRate = v.frameRate,
            bitrateBps = v.bitrateKbps * 1000,
            keyframeIntervalSeconds = v.keyframeIntervalSeconds,
            videoCodec = v.videoCodec,
        )
    }

    fun newAudioCaptureConfig(): AudioCaptureConfig {
        val v = appEngine.config.value.video
        return AudioCaptureConfig(
            codec = v.audioCodec,
            sampleRateHz = v.effectiveAudioSampleRateHz,
        )
    }

    /** MainActivity 拿这个引用绑 Surface preview(平台壳的合理职责)。 */
    fun platformRuntime(): PlatformRuntimeAndroid = runtime

    /**
     * 视频配置变更后触发媒体重建(Activity 在 videoConfigVersion bump 时调)。
     * Wave 4 真重建逻辑挪到 [PlatformRuntimeAndroid.applyVideoConfig] 内部,
     * ViewModel 只派生 CaptureConfig / AudioCaptureConfig 喂进去。
     */
    fun applyCurrentVideoConfig() {
        runtime.applyVideoConfig(newCaptureConfig(), newAudioCaptureConfig())
    }

    companion object {
        private const val MAX_EVENT_LOG = 200

        // 首次启动默认值(全空,强制用户填)
        private const val WVP_IP = ""
        private const val WVP_PORT = 0
        private const val WVP_SERVER_ID = ""
        private const val WVP_DOMAIN = ""
        private const val WVP_PASSWORD = ""
        private const val DEVICE_ID = ""
        private const val VIDEO_CHANNEL_ID = ""
        private const val ALARM_CHANNEL_ID = ""

        fun defaultConfig() = SimConfig(
            gbVersion = GbVersion.V2022,
            server = ServerConfig(
                ip = WVP_IP, port = WVP_PORT,
                serverId = WVP_SERVER_ID, domain = WVP_DOMAIN
            ),
            device = DeviceConfig(
                deviceId = DEVICE_ID,
                videoChannelId = VIDEO_CHANNEL_ID,
                alarmChannelId = ALARM_CHANNEL_ID,
                username = DEVICE_ID,
                password = WVP_PASSWORD,
                frontChannelId = "",
            ),
            transport = TransportType.UDP,
            keepaliveIntervalSeconds = 60,
        )
    }
}

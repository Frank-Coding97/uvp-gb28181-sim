package com.uvp.sim

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SimulatorEngine
import com.uvp.sim.domain.SubscriptionSnapshot
import com.uvp.sim.network.AndroidNetwork
import com.uvp.sim.network.RemoteEndpoint
import com.uvp.sim.network.RtpSender
import com.uvp.sim.network.TransportType
import com.uvp.sim.network.UdpSipTransport
import com.uvp.sim.recording.AndroidRecordingService
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.recording.RecordingState
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Glues the cross-platform [SimulatorEngine] to Android lifecycle.
 *
 * Holds the live SimConfig so the Config screen can save updates. On config
 * change while connected, [updateConfig] tears down + reconnects with the new
 * settings; while disconnected, it just stores the new config for next connect.
 */
class SipViewModel(application: Application) : AndroidViewModel(application) {

    private val engineScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob())
    private val configStore = ConfigStore(application)

    private var transport: com.uvp.sim.network.SipTransport? = null
    private var engine: SimulatorEngine? = null
    private var camera: CameraCapture? = null
    private var audio: AudioCapture? = null
    private var recordingService: RecordingService? = null

    private val _state = MutableStateFlow(SipState.Disconnected)
    val state: StateFlow<SipState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<SimEvent>>(emptyList())
    val events: StateFlow<List<SimEvent>> = _events.asStateFlow()

    private val _config = MutableStateFlow(defaultConfig())
    val config: StateFlow<SimConfig> = _config.asStateFlow()

    /**
     * 当前推流通道名(engine 投影,未连接时为配置的后置通道名)。
     * 注入到 [osdConfig] 的 channelName.text,使 OSD 通道名跟随被叫通道。
     */
    private val _currentChannelName = MutableStateFlow(_config.value.device.videoChannelName)

    /**
     * OSD 视频叠加层配置 — 跟 SimConfig.osd 同步,Streamer 订阅这个 flow 反映 UI 改动。
     * 通道名 text 由运行期 [_currentChannelName] 注入(不再用持久化的 osd.channelName.text),
     * 使烧戳的通道名跟随当前推流的前置/后置通道。
     */
    val osdConfig: StateFlow<com.uvp.sim.config.OsdConfig> =
        combine(_config, _currentChannelName) { cfg, chName ->
            cfg.osd.copy(channelName = cfg.osd.channelName.copy(text = chName))
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            _config.value.osd.let { it.copy(channelName = it.channelName.copy(text = _config.value.device.videoChannelName)) }
        )

    init {
        // OSD 配置变更日志(plan §9 OSD_CONFIG_CHANGED 事件)
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

    /** 录像状态(M2 D 块)。Activity 把它桥接到 AppUiState.recording。 */
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordingFiles = MutableStateFlow<List<RecordingFile>>(emptyList())
    val recordingFiles: StateFlow<List<RecordingFile>> = _recordingFiles.asStateFlow()

    /** 一次性用户提示(toast 等)。任何模块都可以推消息进来,UI 订阅展示一次。
     *  典型用例:录像失败 / 删除完成 / 切片完成等。 */
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /**
     * Bumped each time the video profile changes so the Activity can rebuild
     * its [com.uvp.sim.camera.AndroidCameraStreamer] — encoder MIME / resolution
     * / fps / bitrate / GOP all live there and need a fresh codec instance.
     */
    private val _videoConfigVersion = MutableStateFlow(0)
    val videoConfigVersion: StateFlow<Int> = _videoConfigVersion.asStateFlow()

    private val _subscriptions = MutableStateFlow<Map<String, SubscriptionSnapshot>>(emptyMap())
    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> = _subscriptions.asStateFlow()

    private val _deviceControl = MutableStateFlow(DeviceControlState())
    val deviceControl: StateFlow<DeviceControlState> = _deviceControl.asStateFlow()

    /**
     * 当前生效目录树。SimulatorEngine 是真源,这里作为 UI 投影。
     * 未连接时返回 SimConfig.catalogTree,连接后被 engine 流覆盖。
     */
    private val _catalogTree = MutableStateFlow<List<CatalogNode>>(emptyList())
    val catalogTree: StateFlow<List<CatalogNode>> = _catalogTree.asStateFlow()

    /** 最后一次成功保存目录树的 epoch 毫秒,UI 显示"X 分钟前已保存"。 */
    private val _lastCatalogSavedAt = MutableStateFlow<Long?>(null)
    val lastCatalogSavedAt: StateFlow<Long?> = _lastCatalogSavedAt.asStateFlow()

    /** 本会话报警历史(engine 投影,未连接时为空)。 */
    private val _alarmHistory = MutableStateFlow<List<com.uvp.sim.domain.AlarmRecord>>(emptyList())
    val alarmHistory: StateFlow<List<com.uvp.sim.domain.AlarmRecord>> = _alarmHistory.asStateFlow()

    /** 报警发送模式 + 固定单(本会话内存,spec G2)。 */
    private val _alarmFireMode = MutableStateFlow(com.uvp.sim.ui.AlarmFireMode.Random)
    val alarmFireMode: StateFlow<com.uvp.sim.ui.AlarmFireMode> = _alarmFireMode.asStateFlow()

    private val _fixedAlarm = MutableStateFlow<com.uvp.sim.gb28181.AlarmPayload?>(null)
    val fixedAlarm: StateFlow<com.uvp.sim.gb28181.AlarmPayload?> = _fixedAlarm.asStateFlow()

    /** M3 语音广播下行状态(engine.currentBroadcast 投影)。 */
    private val _broadcast = MutableStateFlow(com.uvp.sim.ui.BroadcastState())
    val broadcast: StateFlow<com.uvp.sim.ui.BroadcastState> = _broadcast.asStateFlow()

    /**
     * 网络选择控制器(T10)。
     * - attach ApplicationContext(避免泄漏 Activity)
     * - state 暴露给 MainActivity 注入到 AppUiState.networkRuntimeState
     * - state 变化时驱动 engine?.handleNetworkChange + 刷新 localIpProvider
     *
     * 启动期会在第三个 init 块按持久化 config.network.preference apply 一次。
     */
    private val networkController = com.uvp.sim.network.NetworkController().apply {
        attach(application)
    }
    val networkState: StateFlow<com.uvp.sim.network.NetworkState> = networkController.state

    init {
        // 启动期按持久化 preference 应用网络偏好,并 collect state 推给引擎
        viewModelScope.launch {
            networkController.apply(_config.value.network.preference)
        }
        viewModelScope.launch {
            networkController.state.collect { netState ->
                engine?.handleNetworkChange(netState)
            }
        }
    }

    /** 老板在网络设置子页点选偏好时调用:持久化 + 应用。 */
    fun applyNetworkPreference(preference: com.uvp.sim.config.NetworkPreference) {
        val updated = _config.value.copy(
            network = _config.value.network.copy(preference = preference)
        )
        updateConfig(updated)
        viewModelScope.launch {
            networkController.apply(preference)
        }
    }

    init {
        // Load persisted config on cold start; bump videoConfigVersion so the
        // Activity rebuilds streamers with the restored encoder params.
        viewModelScope.launch {
            // 双真实通道迁移:老持久化配置无 frontChannelId(反序列化取默认空),
            // 会让 defaultTree 回退为单后置通道。加载时按 domain 补全前置通道 ID,
            // 让老配置自动升级到前后双通道。
            val stored = migrateDualChannel(configStore.loadOnce(defaultConfig()))
            if (stored != _config.value) {
                _config.value = stored
                _videoConfigVersion.value += 1
            }
            // 即便 stored == default,catalogTree 投影也要初始化
            _catalogTree.value = com.uvp.sim.domain.CatalogTreeStore.effectiveTree(stored)
        }
    }

    /** Activity calls this after creating CameraCapture + AndroidCameraStreamer. */
    fun bindCamera(cam: CameraCapture) {
        this.camera = cam
    }

    /**
     * 双真实通道迁移:老配置 frontChannelId 为空时,按 domain 用 IdEncoder 补全前置通道 ID。
     * 已有值则原样返回。补全后写回持久化由后续 save 触发,这里只保证内存态正确。
     */
    private fun migrateDualChannel(cfg: SimConfig): SimConfig {
        if (cfg.device.frontChannelId.isNotBlank()) return cfg
        val frontId = com.uvp.sim.gb28181.IdEncoder.genChildId(
            cfg.server.domain, com.uvp.sim.config.CatalogNodeType.VideoChannel, 2
        )
        return cfg.copy(device = cfg.device.copy(frontChannelId = frontId))
    }

    /** Activity calls this after creating AudioCapture + AndroidAudioStreamer. */
    fun bindAudio(aud: AudioCapture) {
        this.audio = aud
    }

    /**
     * Activity 在 onCreate 时调,把 [AndroidRecordingService] 注入。
     *
     * recordingService 需要 LifecycleOwner + Executor,只有 Activity 能拿到。
     * 注入后 ViewModel 就可以指挥录像 + 在 connect() 时把它传给 SimulatorEngine
     * 让平台 RecordCmd 也能驱动同一个 service。
     */
    fun bindRecordingService(svc: RecordingService) {
        this.recordingService = svc
        engineScope.launch { svc.load() }
        engineScope.launch {
            // 监听状态,Failed → toast(只在进入 Failed 时弹一次,reason 变化也再弹)
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
        engineScope.launch { svc.files.collect { _recordingFiles.value = it } }
    }

    fun startRecording() {
        val svc = recordingService ?: return
        val cfg = _config.value
        engineScope.launch {
            runCatching { svc.start(RecordSource.Manual, cfg.device.videoChannelId) }
        }
    }

    fun stopRecording() {
        val svc = recordingService ?: return
        engineScope.launch { runCatching { svc.stop() } }
    }

    fun deleteRecording(id: String) {
        val svc = recordingService ?: return
        engineScope.launch {
            val result = runCatching { svc.delete(id) }
            if (result.isSuccess) {
                _toasts.tryEmit("已删除")
            } else {
                _toasts.tryEmit("删除失败:${result.exceptionOrNull()?.message ?: "unknown"}")
            }
        }
    }

    /** 清空 SIP 信令事件流(日志页清除按钮触发)。 */
    fun clearSipEvents() {
        _events.value = emptyList()
    }

    fun connect() {
        val existing = engine
        if (existing != null) {
            // Engine already wired up: only kick a fresh REGISTER if we're idle.
            // Failed = previous attempt timed out/4xx, transport is still alive →
            // just retry without rebuilding everything.
            when (_state.value) {
                SipState.Registering, SipState.Registered, SipState.InCall -> return
                SipState.Disconnected, SipState.Failed -> {
                    engineScope.launch {
                        try {
                            // TCP transport 的 socket 可能已经被对端 close / VPN 抖断,
                            // 重连时必须先把底层 socket 也重建一遍。connect() 自身
                            // 已经 idempotent(socket != null 时 noop),所以无脑调安全。
                            transport?.connect()
                            existing.register()
                        } catch (e: Throwable) {
                            _events.update { current ->
                                (listOf(SimEvent.TransportError("register retry: ${e.message}")) + current)
                                    .take(MAX_EVENT_LOG)
                            }
                        }
                    }
                    return
                }
            }
        }
        val cfg = _config.value
        val ctx = getApplication<Application>()
        val fallbackLocalIp = AndroidNetwork.activeIpv4(ctx) ?: "0.0.0.0"

        val tx: com.uvp.sim.network.SipTransport = when (cfg.transport) {
            TransportType.TCP -> com.uvp.sim.network.TcpSipTransport(
                remote = RemoteEndpoint(cfg.server.ip, cfg.server.port, TransportType.TCP),
                parentScope = engineScope
            )
            TransportType.UDP -> UdpSipTransport(
                remote = RemoteEndpoint(cfg.server.ip, cfg.server.port, TransportType.UDP),
                parentScope = engineScope
            )
        }
        transport = tx

        val rtpFactory: (String, Int, com.uvp.sim.network.RtpMode) -> RtpSender = { host, port, mode ->
            RtpSender(host, port, engineScope, mode)
        }
        val pbBuilder = com.uvp.sim.recording.AndroidPlaybackBuilder(
            scope = engineScope,
            rtpSenderFactory = rtpFactory,
            audioCodec = cfg.recording.playbackAudioCodec
        )
        val eng = SimulatorEngine(
            config = cfg,
            transport = tx,
            scope = engineScope,
            localIpProvider = {
                // 网络选择已绑定时用绑定网卡 IP;否则回落系统默认接口
                when (val s = networkController.state.value) {
                    is com.uvp.sim.network.NetworkState.Bound -> s.localIp
                    else -> fallbackLocalIp
                }
            },
            localPortProvider = { tx.localPort.takeIf { it > 0 } ?: 5060 },
            cameraCapture = camera,
            audioCapture = audio,
            rtpSenderFactory = rtpFactory,
            recordingService = recordingService
                ?: com.uvp.sim.recording.NoopRecordingService,
            playbackBuilder = pbBuilder
        )
        engine = eng

        // T10 7.5 抓拍管线: 把 Context-scoped cache + ktor CIO HttpClient 注入引擎
        val snapshotCache = com.uvp.sim.snapshot.JpegLocalCache.forContext(getApplication())
        val snapshotHttp = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
            engine {
                requestTimeout = 30_000
            }
        }
        eng.attachSnapshotPipeline(
            capture = com.uvp.sim.snapshot.SnapshotCapture(),
            cache = snapshotCache,
            httpClient = snapshotHttp
        )
        // 启动期一次 GC: 清 7d 前 / 100MB 上限
        engineScope.launch { snapshotCache.gc() }

        engineScope.launch { eng.state.collect { _state.value = it } }
        engineScope.launch {
            eng.events.collect { ev ->
                _events.update { current ->
                    (listOf(ev) + current).take(MAX_EVENT_LOG)
                }
            }
        }
        engineScope.launch { eng.subscriptions.collect { _subscriptions.value = it } }
        engineScope.launch { eng.deviceControlState.collect { _deviceControl.value = it } }
        engineScope.launch { eng.catalogTree.collect { _catalogTree.value = it } }
        engineScope.launch { eng.alarmHistory.collect { _alarmHistory.value = it } }
        engineScope.launch { eng.currentChannelName.collect { _currentChannelName.value = it } }
        engineScope.launch {
            combine(eng.currentBroadcast, eng.broadcastSpeakerOn) { bc, speakerOn -> bc to speakerOn }
                .collect { (bc, speakerOn) ->
                    _broadcast.value = if (bc == null) {
                        com.uvp.sim.ui.BroadcastState()
                    } else {
                        com.uvp.sim.ui.BroadcastState(
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
                            speakerOn = speakerOn
                        )
                    }
                }
        }

        engineScope.launch {
            try {
                tx.connect()
                eng.register()
            } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(SimEvent.TransportError("connect: ${e::class.simpleName}: ${e.message}")) + current)
                        .take(MAX_EVENT_LOG)
                }
            }
        }
    }

    fun disconnect() {
        val eng = engine ?: return
        engineScope.launch {
            try { eng.unregister() } catch (_: Throwable) { }
            try { eng.shutdown() } catch (_: Throwable) { }
            try { transport?.close() } catch (_: Throwable) { }
            engine = null
            transport = null
        }
    }

    /** Abort an in-flight REGISTER without sending Unregister (we never registered). */
    fun cancelConnect() {
        val eng = engine ?: return
        engineScope.launch {
            try { eng.cancelRegister() } catch (_: Throwable) { }
            try { eng.shutdown() } catch (_: Throwable) { }
            try { transport?.close() } catch (_: Throwable) { }
            engine = null
            transport = null
        }
    }

    /**
     * Save / replace SimConfig. If currently connected, reconnect with new settings;
     * if disconnected, just store for next connect.
     */
    fun updateConfig(newCfg: SimConfig) {
        val prev = _config.value
        _config.value = newCfg
        if (prev.video != newCfg.video) {
            _videoConfigVersion.value += 1
        }
        // Persist asynchronously; UI doesn't need to wait for disk.
        viewModelScope.launch { runCatching { configStore.save(newCfg) } }
        if (engine != null) {
            engineScope.launch {
                try { engine?.unregister() } catch (_: Throwable) { }
                try { engine?.shutdown() } catch (_: Throwable) { }
                try { transport?.close() } catch (_: Throwable) { }
                engine = null
                transport = null
                connect()
            }
        }
    }

    /**
     * 用户保存目录树:
     *  - 先校验:不合法直接返回 Invalid 含错误清单,UI 显示并不持久化
     *  - 通过校验:写回 SimConfig.catalogTree 并持久化(下次冷启动恢复)
     *  - 通知 engine,触发 pushCatalogNotify(若有活跃订阅)
     *
     * 返回值供 UI 显示 toast:Ok 显示成功,Invalid 显示错误。
     */
    fun saveCatalogTree(tree: List<CatalogNode>): com.uvp.sim.domain.ValidationResult {
        val result = com.uvp.sim.domain.CatalogTreeStore.validate(tree)
        if (result is com.uvp.sim.domain.ValidationResult.Invalid) {
            return result
        }
        val newCfg = _config.value.copy(catalogTree = tree)
        _config.value = newCfg
        _catalogTree.value = tree
        _lastCatalogSavedAt.value = System.currentTimeMillis()
        viewModelScope.launch { runCatching { configStore.save(newCfg) } }
        val eng = engine ?: return result
        engineScope.launch {
            try {
                eng.updateCatalogTree(tree)
            } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(SimEvent.TransportError("save catalog: ${e.message}")) + current)
                        .take(MAX_EVENT_LOG)
                }
            }
        }
        return result
    }

    /** Trigger a snapshot upload (T15). Engine handles the rest. */
    fun reportSnapshot() {
        val eng = engine ?: return
        engineScope.launch {
            try { eng.reportSnapshot() } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(SimEvent.TransportError("snapshot: ${e.message}")) + current)
                        .take(MAX_EVENT_LOG)
                }
            }
        }
    }

    /** M2 Alarm — 主动报警(主屏一键 / 能力页详细)。engine 走 reportAlarm fan-out。 */
    fun fireAlarm(payload: com.uvp.sim.gb28181.AlarmPayload) {
        val eng = engine ?: run {
            _toasts.tryEmit("未注册,无法发送报警")
            return
        }
        engineScope.launch {
            try { eng.reportAlarm(payload) } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(SimEvent.TransportError("alarm fire: ${e.message}")) + current)
                        .take(MAX_EVENT_LOG)
                }
            }
        }
    }

    /** M3 — 用户停止语音广播(主屏「对讲中」标签 ✕)。 */
    fun stopBroadcast() {
        val eng = engine ?: return
        engineScope.launch {
            try { eng.stopBroadcast(com.uvp.sim.domain.BroadcastEndReason.Local) } catch (_: Throwable) { }
        }
    }

    /** M3 — 切换对讲扬声器开关(静音/放音)。 */
    fun setBroadcastSpeaker(on: Boolean) {
        engine?.setBroadcastSpeaker(on)
    }

    /** M2 Alarm — 本地复位(不走 SIP)。 */
    fun resetAlarm() {        val eng = engine ?: return
        engineScope.launch {
            try { eng.localResetAlarm() } catch (_: Throwable) { }
        }
    }

    /** M2+ — 主页一点即发,按当前模式选 payload(spec G1/G2)。 */
    fun fireAlarmDefault() {
        val cfg = _config.value
        val payload = when (_alarmFireMode.value) {
            com.uvp.sim.ui.AlarmFireMode.Fixed ->
                _fixedAlarm.value
                    ?: com.uvp.sim.gb28181.AlarmTemplates.random().toPayload(cfg)
            com.uvp.sim.ui.AlarmFireMode.Random ->
                com.uvp.sim.gb28181.AlarmTemplates.random().toPayload(cfg)
        }
        fireAlarm(payload)
    }

    fun setAlarmFireMode(mode: com.uvp.sim.ui.AlarmFireMode) {
        _alarmFireMode.value = mode
    }

    fun saveFixedAlarm(payload: com.uvp.sim.gb28181.AlarmPayload) {
        _fixedAlarm.value = payload
        _alarmFireMode.value = com.uvp.sim.ui.AlarmFireMode.Fixed
    }

    override fun onCleared() {
        super.onCleared()
        try {
            kotlinx.coroutines.runBlocking {
                engine?.shutdown()
                transport?.close()
                camera?.stop()
                networkController.close()
            }
        } catch (_: Throwable) { /* ignore */ }
    }

    fun newCaptureConfig(): CaptureConfig {
        val v = _config.value.video
        return CaptureConfig(
            widthPx = v.resolution.widthPx,
            heightPx = v.resolution.heightPx,
            frameRate = v.frameRate,
            bitrateBps = v.bitrateKbps * 1000,
            keyframeIntervalSeconds = v.keyframeIntervalSeconds,
            videoCodec = v.videoCodec
        )
    }

    fun newAudioCaptureConfig(): AudioCaptureConfig {
        val v = _config.value.video
        return AudioCaptureConfig(
            codec = v.audioCodec,
            sampleRateHz = v.effectiveAudioSampleRateHz
        )
    }

    companion object {
        // ===== WVP target initial defaults; overridable via Config screen =====
        const val WVP_IP = "192.168.10.222"
        const val WVP_PORT = 8160
        const val WVP_SERVER_ID = "35020000002000000001"
        const val WVP_DOMAIN = "3502000000"
        const val WVP_PASSWORD = "wvp_sip_password"

        const val DEVICE_ID = "35020000001310000001"
        const val VIDEO_CHANNEL_ID = "35020000001320000001"
        const val ALARM_CHANNEL_ID = "35020000001340000001"
        // 双真实通道:前置通道 ID 用 IdEncoder 按 domain 生成(VideoChannel, seq=2),
        // 与后置(seq=1 → ...1320000001)区分。
        val FRONT_CHANNEL_ID: String = com.uvp.sim.gb28181.IdEncoder.genChildId(
            WVP_DOMAIN, com.uvp.sim.config.CatalogNodeType.VideoChannel, 2
        )

        const val MAX_EVENT_LOG = 100

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
                frontChannelId = FRONT_CHANNEL_ID
            ),
            transport = TransportType.UDP,
            keepaliveIntervalSeconds = 60
        )
    }
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return
    }
}

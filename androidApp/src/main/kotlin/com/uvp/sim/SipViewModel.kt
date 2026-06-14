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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        // Load persisted config on cold start; bump videoConfigVersion so the
        // Activity rebuilds streamers with the restored encoder params.
        viewModelScope.launch {
            val stored = configStore.loadOnce(defaultConfig())
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
        val localIp = AndroidNetwork.activeIpv4(ctx) ?: "0.0.0.0"

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
            localIp = localIp,
            localPortProvider = { tx.localPort.takeIf { it > 0 } ?: 5060 },
            cameraCapture = camera,
            audioCapture = audio,
            rtpSenderFactory = rtpFactory,
            recordingService = recordingService
                ?: com.uvp.sim.recording.NoopRecordingService,
            playbackBuilder = pbBuilder
        )
        engine = eng

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

    override fun onCleared() {
        super.onCleared()
        try {
            kotlinx.coroutines.runBlocking {
                engine?.shutdown()
                transport?.close()
                camera?.stop()
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
                password = WVP_PASSWORD
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

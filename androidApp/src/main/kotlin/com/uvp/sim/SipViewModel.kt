package com.uvp.sim

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SimulatorEngine
import com.uvp.sim.network.AndroidNetwork
import com.uvp.sim.network.RemoteEndpoint
import com.uvp.sim.network.RtpSender
import com.uvp.sim.network.TransportType
import com.uvp.sim.network.UdpSipTransport
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private var transport: UdpSipTransport? = null
    private var engine: SimulatorEngine? = null
    private var camera: CameraCapture? = null
    private var audio: AudioCapture? = null

    private val _state = MutableStateFlow(SipState.Disconnected)
    val state: StateFlow<SipState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<SimEvent>>(emptyList())
    val events: StateFlow<List<SimEvent>> = _events.asStateFlow()

    private val _config = MutableStateFlow(defaultConfig())
    val config: StateFlow<SimConfig> = _config.asStateFlow()

    /**
     * Bumped each time the video profile changes so the Activity can rebuild
     * its [com.uvp.sim.camera.AndroidCameraStreamer] — encoder MIME / resolution
     * / fps / bitrate / GOP all live there and need a fresh codec instance.
     */
    private val _videoConfigVersion = MutableStateFlow(0)
    val videoConfigVersion: StateFlow<Int> = _videoConfigVersion.asStateFlow()

    init {
        // Load persisted config on cold start; bump videoConfigVersion so the
        // Activity rebuilds streamers with the restored encoder params.
        viewModelScope.launch {
            val stored = configStore.loadOnce(defaultConfig())
            if (stored != _config.value) {
                _config.value = stored
                _videoConfigVersion.value += 1
            }
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
                        try { existing.register() } catch (e: Throwable) {
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

        val tx = UdpSipTransport(
            remote = RemoteEndpoint(cfg.server.ip, cfg.server.port, TransportType.UDP),
            parentScope = engineScope
        )
        transport = tx

        val rtpFactory: (String, Int) -> RtpSender = { host, port ->
            RtpSender(host, port, engineScope)
        }
        val eng = SimulatorEngine(
            config = cfg,
            transport = tx,
            scope = engineScope,
            localIp = localIp,
            localPortProvider = { tx.localPort.takeIf { it > 0 } ?: 5060 },
            cameraCapture = camera,
            audioCapture = audio,
            rtpSenderFactory = rtpFactory
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
        return AudioCaptureConfig(codec = _config.value.video.audioCodec)
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

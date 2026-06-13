package com.uvp.sim

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.uvp.sim.camera.AndroidAudioStreamer
import com.uvp.sim.camera.AndroidCameraStreamer
import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.observability.AndroidSessionStore
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SessionTracker
import com.uvp.sim.observability.SystemLog
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.ui.App
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.CameraPreviewBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: SipViewModel by viewModels()

    private lateinit var cameraCapture: CameraCapture
    private lateinit var audioCapture: AudioCapture
    private var audioStreamer: AndroidAudioStreamer? = null
    private val systemEvents = MutableStateFlow<List<SystemLog>>(emptyList())

    /** 进程级单例,跨 Activity 重建。第一次 onCreate 创建,之后复用。 */
    private var streamerRef: AndroidCameraStreamer?
        get() = sStreamer
        set(value) { sStreamer = value }

    /** 进程级单例,跨 Activity 重建。第一次 onCreate 创建,之后复用。 */
    private var recordingServiceRef: com.uvp.sim.recording.AndroidRecordingService?
        get() = sRecordingService
        set(value) { sRecordingService = value }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) attachStreamer()
            if (result[Manifest.permission.RECORD_AUDIO] == true) attachAudioStreamer()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // observability 接线 — 必须在任何业务 emit 之前
        SessionTracker.install(AndroidSessionStore(applicationContext))
        SystemLogger.bindScope(lifecycleScope)
        installLogcatBridge()
        com.uvp.sim.ui.ShareContextHolder.context = this
        SystemLogger.emit(
            LogLevel.Info, LogTag.Lifecycle,
            "应用启动 · 会话 #${SessionTracker.currentId}"
        )

        cameraCapture = CameraCapture(viewModel.newCaptureConfig())
        audioCapture = AudioCapture(viewModel.newAudioCaptureConfig())
        viewModel.bindCamera(cameraCapture)
        viewModel.bindAudio(audioCapture)

        // M2 录像服务 — 与 AndroidCameraStreamer 共享同一 ProcessCameraProvider
        // 单例 + streamer 的自驱 STARTED LifecycleOwner。
        // 切后台 / Activity 重建时 streamer 的 lifecycle 仍 STARTED,所以录像和
        // 实时推流都不会被系统自动 unbind 干掉(切后台不录像 / 预览黑屏的根因)。
        if (recordingServiceRef == null) {
            recordingServiceRef = com.uvp.sim.recording.AndroidRecordingService(
                context = applicationContext,
                streamerSupplier = {
                    sStreamer ?: error("streamer must be initialized before recording")
                },
                executor = ContextCompat.getMainExecutor(applicationContext),
                deviceId = viewModel.config.value.device.deviceId,
                scope = AppScope.scope
            )
            viewModel.bindRecordingService(recordingServiceRef!!)
        }

        val needs = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) attachStreamer() else needs += Manifest.permission.CAMERA

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) attachAudioStreamer() else needs += Manifest.permission.RECORD_AUDIO

        if (needs.isNotEmpty()) requestPermissions.launch(needs.toTypedArray())

        setContent {
            val sipState by viewModel.state.collectAsStateWithLifecycle()
            val events by viewModel.events.collectAsStateWithLifecycle()
            val config by viewModel.config.collectAsStateWithLifecycle()
            val videoVersion by viewModel.videoConfigVersion.collectAsStateWithLifecycle()
            val sysLogs by systemEvents.collectAsState()
            val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
            val recordingFiles by viewModel.recordingFiles.collectAsStateWithLifecycle()
            val recordingStatus = remember(recordingState, recordingFiles) {
                val rec = recordingState as? com.uvp.sim.recording.RecordingState.Recording
                val failed = recordingState as? com.uvp.sim.recording.RecordingState.Failed
                com.uvp.sim.ui.RecordingStatus(
                    isRecording = rec != null,
                    source = rec?.source,
                    startMs = rec?.startMs,
                    segmentIndex = rec?.segmentIndex ?: 0,
                    lastError = failed?.reason,
                    files = recordingFiles
                )
            }
            val uiState = AppUiState(
                sip = sipState,
                config = config,
                events = events,
                systemEvents = sysLogs,
                sessionMarker = SessionTracker.current,
                recording = recordingStatus
            )
            val actions = object : AppActions {
                override fun onConnect() {
                    SystemLogger.emit(LogLevel.Info, LogTag.User, "用户点击注册")
                    viewModel.connect()
                }
                override fun onCancelConnect() {
                    SystemLogger.emit(LogLevel.Info, LogTag.User, "用户点击取消注册")
                    viewModel.cancelConnect()
                }
                override fun onDisconnect() {
                    SystemLogger.emit(LogLevel.Info, LogTag.User, "用户点击注销")
                    viewModel.disconnect()
                }
                override fun onSnapshot() {
                    SystemLogger.emit(LogLevel.Info, LogTag.User, "用户点击抓拍")
                    viewModel.reportSnapshot()
                }
                override fun onConfigSave(updated: com.uvp.sim.config.SimConfig) {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.User,
                        "配置已更新 device=${updated.device.deviceId} server=${updated.server.ip}:${updated.server.port}"
                    )
                    viewModel.updateConfig(updated)
                }
                override fun onRecordingStart() {
                    SystemLogger.emit(LogLevel.Info, LogTag.User, "用户点击开始录像")
                    viewModel.startRecording()
                }
                override fun onRecordingStop() {
                    SystemLogger.emit(LogLevel.Info, LogTag.User, "用户点击停止录像")
                    viewModel.stopRecording()
                }
                override fun onRecordingDelete(id: String) {
                    SystemLogger.emit(LogLevel.Info, LogTag.User, "用户删除录像 $id")
                    viewModel.deleteRecording(id)
                }
            }
            // Rebuild encoder/streamer whenever video profile bumps.
            LaunchedEffect(videoVersion) {
                if (videoVersion > 0) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) attachStreamer()
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED) attachAudioStreamer()
                }
            }
            App(state = uiState, actions = actions)
        }
    }

    override fun onStart() {
        super.onStart()
        SystemLogger.emit(LogLevel.Info, LogTag.Lifecycle, "前台恢复")
    }

    override fun onStop() {
        super.onStop()
        SystemLogger.emit(LogLevel.Info, LogTag.Lifecycle, "进入后台")
    }

    override fun onDestroy() {
        com.uvp.sim.ui.ShareContextHolder.context = null
        CameraPreviewBinder.setBinder(null)
        super.onDestroy()
    }

    /**
     * 把 SystemLogger.flow 桥接到 Android logcat。
     *
     * 期 1-2 阶段没有专属 UI,运维和 overnight 自动跑都靠这里看事件。
     * 期 3 上 SystemLogTab 后保留 — logcat 是诊断兜底,任何时候都有用。
     */
    private fun installLogcatBridge() {
        lifecycleScope.launch {
            SystemLogger.flow.collect { log ->
                val priority = when (log.level) {
                    LogLevel.Debug -> Log.DEBUG
                    LogLevel.Info -> Log.INFO
                    LogLevel.Warning -> Log.WARN
                    LogLevel.Error -> Log.ERROR
                }
                val line = "[#${log.sessionId}][${log.tag.display}] ${log.message}"
                Log.println(priority, TAG_SYS, line)
                log.detail?.let { Log.println(priority, TAG_SYS, "  ↳ $it") }
                // 同步给 UI(SystemLogger.snapshot 在 actor 内已写入,这里聚合给 Compose state)
                systemEvents.value = SystemLogger.snapshot
            }
        }
    }

    private fun attachStreamer() {
        // streamer 是进程级单例:首次创建,后续 Activity 重建复用同一实例。
        // 这样切后台 / 旋屏时 streamer 的自驱 lifecycle 不被销毁,CameraX 不会
        // 自动 unbind 录像 / 推流的 use cases。
        val s = streamerRef ?: AndroidCameraStreamer(
            context = applicationContext,
            mainExecutor = ContextCompat.getMainExecutor(applicationContext),
            config = viewModel.newCaptureConfig()
        ).also { streamerRef = it }
        cameraCapture.setStreamer(s)
        CameraPreviewBinder.setBinder { view ->
            if (view != null) s.attachPreviewView(view) else s.detachPreviewView()
        }
    }

    private fun attachAudioStreamer() {
        audioStreamer?.let { old ->
            kotlinx.coroutines.runBlocking { runCatching { old.stop() } }
        }
        val s = AndroidAudioStreamer(viewModel.newAudioCaptureConfig())
        audioStreamer = s
        audioCapture.setStreamer(s)
    }

    companion object {
        private const val TAG_SYS = "SystemLogger"

        /** 进程级单例,跨 Activity 重建。 */
        @Volatile
        private var sStreamer: AndroidCameraStreamer? = null

        /** 进程级单例,跨 Activity 重建。 */
        @Volatile
        private var sRecordingService: com.uvp.sim.recording.AndroidRecordingService? = null
    }
}

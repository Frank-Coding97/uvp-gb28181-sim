package com.uvp.sim

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.uvp.sim.observability.AndroidSessionStore
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.ui.model.mapper.toDto
import com.uvp.sim.observability.SessionTracker
import com.uvp.sim.observability.SystemLog
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.ui.App
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.SubscriptionKind
import com.uvp.sim.ui.SubscriptionStatus
import com.uvp.sim.ui.CameraPreviewBinder
import com.uvp.sim.ui.actions.CapabilityActions
import com.uvp.sim.ui.actions.HomeActions
import com.uvp.sim.ui.actions.NetworkActions
import com.uvp.sim.ui.actions.RecordingActions
import com.uvp.sim.ui.actions.logged
import com.uvp.sim.ui.actions.loggedR
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Android 平台壳 — Wave 4 PR-PLATFORM-RUNTIME 后大瘦身。
 *
 * 职责:
 *   1. observability 接线(SessionTracker / SystemLogger / logcat / toast bridge)
 *   2. 权限请求(CAMERA / RECORD_AUDIO),拿到后 ensure 媒体已装好(runtime 已在 VM init 时装)
 *   3. Surface preview 通过 [CameraPreviewBinder] 路由到 [com.uvp.sim.app.PlatformRuntimeAndroid]
 *   4. videoConfigVersion bump 时调 viewModel.applyCurrentVideoConfig()
 *   5. Compose UI 注入 AppActions
 *
 * 不再做:
 *   - new CameraCapture / new AudioCapture / new AndroidRecordingService(挪到 PlatformRuntimeAndroid)
 *   - new AndroidCameraStreamer / new AndroidAudioStreamer(挪到 PlatformRuntimeAndroid)
 *   - companion sStreamer / sRecordingService 单例(挪到 PlatformRuntimeAndroid 内部 @Volatile)
 *   - attachStreamer / attachAudioStreamer 9 参装配
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SipViewModel by viewModels()
    private val systemEvents = MutableStateFlow<List<SystemLog>>(emptyList())

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // 权限拿到后,触发一次 applyCurrentVideoConfig 让 runtime 把当前 streamer rebind 到 facade
            // (首次 buildCameraCapture/buildAudioCapture 已在 ViewModel init 跑过,这里只确保用户授权后
            //  capture facade 拿到的 streamer 状态对齐)
            if (result[Manifest.permission.CAMERA] == true ||
                result[Manifest.permission.RECORD_AUDIO] == true) {
                viewModel.applyCurrentVideoConfig()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // observability 接线 — 必须在任何业务 emit 之前
        SessionTracker.install(AndroidSessionStore(applicationContext))
        SystemLogger.bindScope(lifecycleScope)
        installLogcatBridge()
        installToastBridge()
        com.uvp.sim.ui.ShareContextHolder.context = this
        SystemLogger.emit(
            LogLevel.Info, LogTag.Lifecycle,
            "应用启动 · 会话 #${SessionTracker.currentId}"
        )

        // Surface 预览路由 — Compose 端 SurfaceView attach/detach 通过 binder 落到 runtime
        val runtime = viewModel.platformRuntime()
        CameraPreviewBinder.setBinder { view ->
            if (view != null) runtime.attachPreviewSurface(view)
            else runtime.detachPreviewSurface()
        }

        // 权限检查 — 没拿到就请求,拿到后媒体已就绪
        // v1 real-gps-source(plan §5.2):MobilePosition 上报需要 FINE_LOCATION,COARSE 兜底
        val needs = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) needs += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) needs += Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) needs += Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) needs += Manifest.permission.ACCESS_COARSE_LOCATION
        if (needs.isNotEmpty()) requestPermissions.launch(needs.toTypedArray())

        setContent {
            val sipState by viewModel.state.collectAsStateWithLifecycle()
            val events by viewModel.events.collectAsStateWithLifecycle()
            val config by viewModel.config.collectAsStateWithLifecycle()
            val videoVersion by viewModel.videoConfigVersion.collectAsStateWithLifecycle()
            val sysLogs by systemEvents.collectAsState()
            val rawSubs by viewModel.subscriptions.collectAsStateWithLifecycle()
            val deviceControl by viewModel.deviceControl.collectAsStateWithLifecycle()
            val catalogTree by viewModel.catalogTree.collectAsStateWithLifecycle()
            val lastCatalogSavedAt by viewModel.lastCatalogSavedAt.collectAsStateWithLifecycle()
            val alarmHistory by viewModel.alarmHistory.collectAsStateWithLifecycle()
            val alarmFireMode by viewModel.alarmFireMode.collectAsStateWithLifecycle()
            val fixedAlarm by viewModel.fixedAlarm.collectAsStateWithLifecycle()
            val broadcast by viewModel.broadcast.collectAsStateWithLifecycle()
            val networkState by viewModel.networkState.collectAsStateWithLifecycle()
            val clockOffset by viewModel.clockOffset.collectAsStateWithLifecycle()
            val subscriptions = rawSubs.mapNotNull { (kind, snap) ->
                val key = try { SubscriptionKind.valueOf(kind) } catch (_: Exception) { null }
                    ?: return@mapNotNull null
                key to SubscriptionStatus(
                    active = snap.active,
                    subscriber = snap.subscriber,
                    expiresSeconds = snap.expiresSeconds,
                    remainingSeconds = snap.remainingSeconds,
                    notifyCount = snap.notifyCount
                )
            }.toMap()
            val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
            val recordingFiles by viewModel.recordingFiles.collectAsStateWithLifecycle()
            val recordingStatus = remember(recordingState, recordingFiles) {
                val rec = recordingState as? com.uvp.sim.recording.RecordingState.Recording
                val failed = recordingState as? com.uvp.sim.recording.RecordingState.Failed
                com.uvp.sim.ui.RecordingStatus(
                    isRecording = rec != null,
                    source = rec?.source?.toDto(),
                    startMs = rec?.startMs,
                    segmentIndex = rec?.segmentIndex ?: 0,
                    lastError = failed?.reason,
                    files = recordingFiles.map { it.toDto() }
                )
            }
            val uiState = AppUiState(
                sip = sipState.toDto(),
                config = config,
                events = events.map { it.toDto() },
                systemEvents = sysLogs.map { it.toDto() },
                sessionMarker = SessionTracker.current?.toDto(),
                subscriptions = subscriptions,
                deviceControl = deviceControl.toDto(),
                recording = recordingStatus,
                catalogTree = catalogTree,
                lastCatalogSavedAt = lastCatalogSavedAt,
                alarmHistory = alarmHistory.map { it.toDto() },
                alarmFireMode = alarmFireMode,
                fixedAlarmTemplate = fixedAlarm?.toDto(),
                broadcast = broadcast,
                networkRuntimeState = networkState.toDto(),
                clockOffset = clockOffset.toDto(),
            )
            val homeActions = object : HomeActions {
                override fun onConnect() = logged("用户点击注册") { viewModel.connect() }
                override fun onCancelConnect() = logged("用户点击取消注册") { viewModel.cancelConnect() }
                override fun onDisconnect() = logged("用户点击注销") { viewModel.disconnect() }
                override fun onConfigSave(updated: com.uvp.sim.config.SimConfig) = logged(
                    "配置已更新 device=${updated.device.deviceId} server=${updated.server.ip}:${updated.server.port}"
                ) { viewModel.updateConfig(updated) }
                override fun onClearSipLogs() = logged("用户清除 SIP 日志") { viewModel.clearSipEvents() }
                override fun onClearSystemLogs() {
                    // emit 先入队,Clear 在后,actor 串行处理后这条 emit 会随旧 buffer 一起被清掉。
                    // 故意保留 emit 是为了让 logcat bridge 留下一条审计记录(开发者可查)。
                    SystemLogger.emit(LogLevel.Info, LogTag.User, "用户清除系统日志")
                    SystemLogger.clear()
                }
                override fun onConsumeDeviceEffect() { viewModel.consumeDeviceEffect() }
            }
            val capabilityActions = object : CapabilityActions {
                override fun onSnapshot() = logged("用户点击抓拍") { viewModel.reportSnapshot() }
                override fun onAlarmFire(payload: com.uvp.sim.gb28181.AlarmPayload) = logged(
                    "用户发送报警 type=${payload.type.label} priority=${payload.priority.label}"
                ) { viewModel.fireAlarm(payload) }
                override fun onAlarmReset() = logged("用户本地复位报警") { viewModel.resetAlarm() }
                override fun onAlarmFireDefault() = logged("主页一键报警(模式驱动)") {
                    viewModel.fireAlarmDefault()
                }
                override fun onSetAlarmFireMode(mode: com.uvp.sim.ui.AlarmFireMode) = logged(
                    "切换报警模式 → $mode"
                ) { viewModel.setAlarmFireMode(mode) }
                override fun onSaveFixedAlarm(payload: com.uvp.sim.gb28181.AlarmPayload) = logged(
                    "保存固定报警单 type=${payload.type.label}"
                ) { viewModel.saveFixedAlarm(payload) }
                override fun onSimulateMediaStatusAbnormal(notifyType: Int) {
                    val label = when (notifyType) {
                        122 -> "录像异常"
                        123 -> "存储满"
                        else -> "未知($notifyType)"
                    }
                    logged("高级模拟 → MediaStatus NotifyType=$notifyType ($label)") {
                        viewModel.simulateMediaStatusAbnormal(notifyType)
                    }
                }
                override fun onBroadcastStop() = logged("用户停止语音对讲") { viewModel.stopBroadcast() }
                override fun onBroadcastToggleSpeaker(on: Boolean) = logged(
                    "用户${if (on) "开启" else "静音"}对讲扬声器"
                ) { viewModel.setBroadcastSpeaker(on) }
                override fun onCatalogTreeSave(tree: List<com.uvp.sim.config.CatalogNode>): String? =
                    loggedR("保存目录树 节点数=${tree.size}") {
                        val result = viewModel.saveCatalogTree(tree)
                        if (result is com.uvp.sim.domain.ValidationResult.Invalid) result.message else null
                    }
                override fun onToggleChannelStatus(channelId: String, online: Boolean) = logged(
                    "用户切换通道状态 $channelId → ${if (online) "ON" else "OFF"}"
                ) { viewModel.toggleChannelStatus(channelId, online) }
                override fun onPoseTick(pan: Float, tilt: Float, zoom: Float) {
                    viewModel.updatePoseFromRender(pan, tilt, zoom)
                }
            }
            val recordingActions = object : RecordingActions {
                override fun onRecordingStart() = logged("用户点击开始录像") { viewModel.startRecording() }
                override fun onRecordingStop() = logged("用户点击停止录像") { viewModel.stopRecording() }
                override fun onRecordingDelete(id: String) = logged("用户删除录像 $id") {
                    viewModel.deleteRecording(id)
                }
                override fun onRecordingFilterApply(filter: com.uvp.sim.recording.RecordingFilter) {
                    // M3 后续:用 GB28181 RecordInfo 查询 + 服务端过滤。
                    // 当前 RecordingScreen 是本地索引筛选(纯 UI),不需要落 ViewModel。
                }
            }
            val networkActions = object : NetworkActions {
                override fun onNetworkPreferenceChange(preference: com.uvp.sim.config.NetworkPreference) =
                    logged("用户切换网络偏好 → ${preference.name}") {
                        viewModel.applyNetworkPreference(preference)
                    }
            }
            val actions = object : AppActions,
                HomeActions by homeActions,
                CapabilityActions by capabilityActions,
                RecordingActions by recordingActions,
                NetworkActions by networkActions {}
            // Video config 变更触发 streamer 真重建 — 委托给 PlatformRuntimeAndroid.applyVideoConfig
            LaunchedEffect(videoVersion) {
                if (videoVersion > 0) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.applyCurrentVideoConfig()
                    }
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

    /** 订阅 ViewModel 一次性消息流,转成系统 Toast。录像失败 / 删除完成等。 */
    private fun installToastBridge() {
        lifecycleScope.launch {
            viewModel.toasts.collect { msg ->
                android.widget.Toast.makeText(
                    this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        private const val TAG_SYS = "SystemLogger"
    }
}

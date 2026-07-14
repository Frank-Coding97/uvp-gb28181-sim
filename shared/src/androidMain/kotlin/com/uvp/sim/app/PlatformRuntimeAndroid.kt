package com.uvp.sim.app

import android.content.Context
import androidx.core.content.ContextCompat
import com.uvp.sim.camera.AndroidAudioStreamer
import com.uvp.sim.camera.AndroidCameraStreamer
import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.RecordingProfile
import com.uvp.sim.recording.AndroidRecordingService
import com.uvp.sim.recording.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Android 运行时装配(Wave 4 PR-PLATFORM-RUNTIME)。
 *
 * 接管 MainActivity 原本散开的 9 参 `new AndroidCameraStreamer` / `new AndroidAudioStreamer` /
 * `new AndroidRecordingService` 装配。MainActivity 退化为壳:创建 runtime + resources + AppEngine,
 * 然后处理 Activity 生命周期 + Surface preview 绑定。
 *
 * 单例所有权(P3-3 顺手统一):
 *   - 进程级 [streamer] / [recordingService] — 原 MainActivity companion @Volatile,现挪到 runtime 内部
 *   - Activity 重建不影响 streamer 的自驱 STARTED lifecycle,CameraX 不会自动 unbind 直播/录像
 *
 * Video config bump(PR-USER-BUG-1):
 *   - [applyVideoConfig] 拿到新 captureConfig:不同就 release 旧 streamer + new + setStreamer
 *   - 同值走 applyCaptureConfig short-circuit
 *
 * OSD 流水线:
 *   - osdConfigFlow 装到 streamer,跟 SimConfig.osd 同源(SipViewModel 派生)
 *   - OSD 配置变更 → streamer 内部 OsdRenderer 实时更新,不需要 streamer 重建
 */
class PlatformRuntimeAndroid(
    private val context: Context,
    private val osdConfigSupplier: () -> StateFlow<OsdConfig>?,
) : PlatformRuntime {

    private val mainExecutor = ContextCompat.getMainExecutor(context.applicationContext)
    private val appContext: Context get() = context.applicationContext

    /**
     * 进程级 streamer / audioStreamer / recordingService 单例。
     * MainActivity 重建时 runtime 沿用既有实例,CameraX 用例不被 unbind。
     */
    @Volatile private var streamer: AndroidCameraStreamer? = null
    @Volatile private var audioStreamer: AndroidAudioStreamer? = null
    @Volatile private var cameraCaptureRef: CameraCapture? = null
    @Volatile private var audioCaptureRef: AudioCapture? = null
    @Volatile private var recordingServiceRef: AndroidRecordingService? = null

    /** 当前 capture config 缓存 — applyVideoConfig 用于 diff 是否需要真重建。 */
    @Volatile private var currentCaptureConfig: CaptureConfig? = null

    override fun buildCameraCapture(config: CaptureConfig): CameraCapture {
        val cam = cameraCaptureRef ?: CameraCapture(config).also { cameraCaptureRef = it }
        val s = ensureStreamer(config)
        cam.setStreamer(s)
        return cam
    }

    override fun buildAudioCapture(config: AudioCaptureConfig): AudioCapture {
        val a = audioCaptureRef ?: AudioCapture(config).also { audioCaptureRef = it }
        val s = AndroidAudioStreamer(config)
        // 旧 streamer 异步 stop 让位(2s 超时兜底原本在 MainActivity 里,这里改成 fire-and-forget 简化:
        // streamer.stop 实现已经在内部用 mutex + lazy release,不会阻塞调用线程)
        audioStreamer?.let { old -> runCatching { runReleaseAsync(old) } }
        audioStreamer = s
        a.setStreamer(s)
        return a
    }

    override fun buildRecordingService(
        scope: CoroutineScope,
        deviceIdSupplier: () -> String,
        encoderConfigSupplier: () -> RecordingEncoderConfig,
        osdConfigSupplier: () -> StateFlow<OsdConfig>,
        profileSupplier: () -> RecordingProfile,
    ): RecordingService {
        val existing = recordingServiceRef
        if (existing != null) return existing
        val svc = AndroidRecordingService(
            context = appContext,
            executor = mainExecutor,
            deviceIdSupplier = deviceIdSupplier,
            scope = scope,
            osdConfigSupplier = osdConfigSupplier,
            encoderConfigSupplier = {
                val cfg = encoderConfigSupplier()
                AndroidRecordingService.EncoderConfig(
                    widthPx = cfg.widthPx,
                    heightPx = cfg.heightPx,
                    frameRate = cfg.frameRate,
                    bitrateBps = cfg.bitrateBps,
                    keyframeIntervalSeconds = cfg.keyframeIntervalSeconds,
                )
            },
            profileSupplier = profileSupplier,
        )
        recordingServiceRef = svc
        return svc
    }

    /**
     * plan §4.3 T4 stub — 先返回 MockGpsSource 让编译 / 现有测试继续过。
     * T5 会把返回值替换为真实的 [com.uvp.sim.domain.location.AndroidSystemLocationProvider]。
     */
    override fun buildLocationProvider(startPoint: com.uvp.sim.config.GeoPoint):
        com.uvp.sim.domain.location.LocationProvider =
        com.uvp.sim.domain.MockGpsSource(startPoint)

    override fun applyVideoConfig(captureConfig: CaptureConfig, audioConfig: AudioCaptureConfig) {
        val cam = cameraCaptureRef ?: return
        // P2-2(2026-06-28):不再粗暴 release + new 整个 streamer ——
        // streamer.applyCaptureConfig 内部已经会按分辨率变化重建 OSD pipeline
        // 并自动把当前挂着的 SurfaceView 重新 attach 到新 renderer。
        // 老路径每次配置变都 new streamer + 等 Compose 重组 reattach,中间 SurfaceView 短暂黑屏,
        // 而且 cameraCapture / recordingService 内部对 streamer 的引用也都得跟着轮换,容易漏。
        val s = ensureStreamer(captureConfig)
        s.applyCaptureConfig(captureConfig)
        currentCaptureConfig = captureConfig
        cam.setStreamer(s)

        // Audio:每次都 new 一个新 streamer(旧 stop 异步兜底)
        val audio = audioCaptureRef ?: return
        val newAudioStreamer = AndroidAudioStreamer(audioConfig)
        audioStreamer?.let { old -> runReleaseAsync(old) }
        audioStreamer = newAudioStreamer
        audio.setStreamer(newAudioStreamer)
    }

    override suspend fun release() {
        runCatching { streamer?.release() }
        streamer = null
        // P1-2(2026-06-28):同步等 audio streamer.stop 完成,不再 fire-and-forget。
        // 2s 超时兜底放在这里(原 runReleaseAsync 的语义),调用方(ViewModel.onCleared)
        // 自己再叠一层 5s timeout 控制总 SLA。
        audioStreamer?.let { old ->
            kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                runCatching { old.stop() }
            }
        }
        audioStreamer = null
        cameraCaptureRef = null
        audioCaptureRef = null
        recordingServiceRef = null
        currentCaptureConfig = null
    }

    /**
     * Activity 调用:把 SurfaceView 绑到 streamer,Surface 创建/销毁通过 Holder.Callback 同步给
     * 内部 OsdRenderer。MainActivity 不再持 streamer 引用 — 通过 runtime 委托。
     *
     * 调用时机:MainActivity onCreate 完成 + 权限拿到后 attachPreviewView,onDestroy 时 detach。
     * Compose 端的 CameraPreviewBinder 仍负责 SurfaceView lifecycle,这里只是收口入口。
     */
    fun attachPreviewSurface(view: android.view.SurfaceView) {
        streamer?.attachPreviewView(view)
    }

    fun detachPreviewSurface() {
        streamer?.detachPreviewView()
    }

    private fun ensureStreamer(config: CaptureConfig): AndroidCameraStreamer {
        val existing = streamer
        if (existing != null) return existing
        val fresh = AndroidCameraStreamer(
            context = appContext,
            mainExecutor = mainExecutor,
            config = config,
            osdConfigFlow = osdConfigSupplier(),
        )
        streamer = fresh
        currentCaptureConfig = config
        return fresh
    }

    /** AndroidAudioStreamer.stop 是 suspend;这里 fire-and-forget,2s 超时兜底。 */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun runReleaseAsync(old: AndroidAudioStreamer) {
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                runCatching { old.stop() }
            }
        }
    }
}

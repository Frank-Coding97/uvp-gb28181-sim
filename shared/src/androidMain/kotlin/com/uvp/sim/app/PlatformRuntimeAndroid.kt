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

    override fun applyVideoConfig(captureConfig: CaptureConfig, audioConfig: AudioCaptureConfig) {
        val cam = cameraCaptureRef ?: return
        val needsRebuild = currentCaptureConfig != null && currentCaptureConfig != captureConfig
        val s = if (needsRebuild) {
            // 真重建:release 旧 streamer + new 一个,facade 重绑
            runCatching { streamer?.release() }
            val fresh = AndroidCameraStreamer(
                context = appContext,
                mainExecutor = mainExecutor,
                config = captureConfig,
                osdConfigFlow = osdConfigSupplier(),
            )
            streamer = fresh
            currentCaptureConfig = captureConfig
            fresh
        } else {
            ensureStreamer(captureConfig)
        }
        // 即便复用旧 streamer 也把最新 CaptureConfig 推给它 — applyCaptureConfig
        // 同值 short-circuit 不抖,值变会 release 当前 encoder 让下一轮 stream 起新 codec。
        s.applyCaptureConfig(captureConfig)
        cam.setStreamer(s)

        // Audio:每次都 new 一个新 streamer(旧 stop 异步兜底)
        val audio = audioCaptureRef ?: return
        val newAudioStreamer = AndroidAudioStreamer(audioConfig)
        audioStreamer?.let { old -> runReleaseAsync(old) }
        audioStreamer = newAudioStreamer
        audio.setStreamer(newAudioStreamer)
    }

    override fun release() {
        runCatching { streamer?.release() }
        streamer = null
        runCatching { audioStreamer?.let { runReleaseAsync(it) } }
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

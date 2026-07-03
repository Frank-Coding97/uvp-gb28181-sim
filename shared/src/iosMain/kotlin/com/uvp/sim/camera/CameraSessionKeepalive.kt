package com.uvp.sim.camera

import com.uvp.sim.api.LogLevel
import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * iOS 相机会话保活器 — 在 SIP 注册状态下常驻 collect [IosCameraStreamer.stream],
 * 让 AVCaptureSession 一直活跃,从而:
 *   - PlatformCameraPreview 挂的 AVCaptureVideoPreviewLayer 有画面(session 已 publish)
 *   - IosRecordingService 起录时能通过 IosRecordingFrameBridge 拿到实时 H.264 帧
 *
 * 2026-07-03 真机验发现:iOS 端 stream() 是 callbackFlow,只有 collector 才会
 * wireCaptureSession → publish session。之前只 INVITE 拉流路径 collect,导致
 * "注册后预览白屏" + "起录后 writer 收不到 sample stop 时炸"两 bug 同源。
 *
 * 生命周期:
 *   - [start] 建 streamer + 起 collector job(drop 帧)。已在运行时幂等 no-op
 *   - [stop] cancel job + 触发 IosCameraStreamer.releaseInternal(通过 awaitClose)
 *
 * 冲突:InCall 时 AppEngine 会独立 build CameraCapture 抢 AVCaptureSession。
 * 调用方(IosAppHost)应在 InCall 前后 stop 让 AppEngine 拥有 session。
 */
object CameraSessionKeepalive {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var streamer: IosCameraStreamer? = null

    /** 幂等:已在跑就 no-op。 */
    fun start(config: CaptureConfig) {
        if (job?.isActive == true) return
        val s = IosCameraStreamer(config)
        streamer = s
        job = scope.launch {
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_CAMERA_KEEPALIVE_START ${config.widthPx}x${config.heightPx}@${config.frameRate}"
            )
            runCatching {
                // Drop frames — bridge (IosCameraStreamer.SampleReceiver.onEncoded)
                // already forwards each encoded frame to IosRecordingFrameBridge
                // for the recording pipeline. This collector's only purpose is to
                // keep wireCaptureSession alive so the session stays published.
                s.stream().collect { /* discard */ }
            }.onFailure {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "IOS_CAMERA_KEEPALIVE_FAIL msg=${it.message}"
                )
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        streamer = null
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_CAMERA_KEEPALIVE_STOP")
    }
}

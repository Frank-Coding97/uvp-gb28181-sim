package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.media.H264Frame
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * iOS implementation of [CameraCapture] — v1.3-A T-P4-1 后 delegate 到 [IosCameraController]。
 *
 * v1.2 时期:每次 [start] 新建 [IosCameraStreamer](内含独立 AVCaptureSession + VT session),
 * 跟 [CameraSessionKeepalive] 各持一份 → 多个 session 抢 back camera 靠 IosAppHost 时序避让。
 *
 * v1.3-A 后:CameraCapture 是 controller.requestEncoding 的**消费者之一**,跟录像共享
 * 引用计数下的同一 VT session。back camera 只被 controller 一路持有,不再有争抢。
 *
 * commonMain `expect class CameraCapture` 签名**零改动**,Android + JVM actual 不动。
 */
actual class CameraCapture actual constructor(private val config: CaptureConfig) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var handle: EncodingHandle? = null

    /**
     * Legacy hook — v1.2 tests 用它注入 pre-built streamer。v1.3-A 后 no-op(controller 独立管
     * 相机 lifecycle),留签名兼容避免测试引用点编译红。
     */
    @Suppress("UNUSED_PARAMETER")
    fun setStreamer(streamer: IosCameraStreamer?) {
        SystemLogger.emit(
            LogLevel.Debug, LogTag.Media,
            "CameraCapture.setStreamer no-op (v1.3-A T-P4-1: controller manages camera lifecycle)"
        )
    }

    actual fun start(): Flow<H264Frame> {
        // 确保 controller 有 preview 拿到帧源;keepalive 通常已启,这里 launch 是幂等兜底
        // (controller.startPreview 内部 mutex + captureSession non-null 判断保证 no-op)
        scope.launch { IosCameraController.startPreview(config) }

        // Stash config 给 requestEncoding 用(currentConfig 通常已由 startPreview 设置,兜底极端 race)
        IosCameraController.stashConfigForEncoding(config)

        val h = IosCameraController.requestEncoding()
        handle = h
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "CameraCapture.start via controller ${config.widthPx}x${config.heightPx}@${config.frameRate}"
        )
        return h.frames
    }

    actual suspend fun stop() {
        handle?.close()
        handle = null
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "CameraCapture.stop handle closed")
    }

    actual fun requestKeyFrame() {
        IosCameraController.requestKeyFrame()
    }

    @Volatile
    private var pendingFacing: CameraFacing = config.cameraFacing

    /**
     * iOS v1.1/v1.3 只支持后置摄像头(plan DEC-4)。前置切换请求记录 pending 但不影响
     * running session,warning log 让平台侧看到被忽略的指令。真双摄切换见 [dual-camera-channel]。
     */
    actual fun setFacing(facing: CameraFacing) {
        pendingFacing = facing
        if (facing != CameraFacing.BACK) {
            SystemLogger.emit(
                level = LogLevel.Warning,
                tag = LogTag.Media,
                message = "iOS only supports back camera; ignoring switch to $facing",
                detail = "pending target recorded but not applied — dual-camera lands separately"
            )
        }
    }
}

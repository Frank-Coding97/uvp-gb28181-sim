package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * iOS 相机会话保活器 — v1.3-A T-P3-1 后为薄壳,直接 delegate 到 [IosCameraController]。
 *
 * v1.2 时期:内部 collect [IosCameraStreamer.stream] callbackFlow 让 wireCaptureSession
 * 触发 → publish session 到 [IosCameraSessionHolder]。**耦合了 VT encoding**(handoff bug 2 修复
 * 副作用 → v1.3-A 卡顿根因)。
 *
 * v1.3-A 后:startPreview 只启 AVCaptureSession + delegate,**不启 VTCompressionSession**。
 * encoding 由 INVITE 拉流 / 录像 各自通过 [IosCameraController.requestEncoding] 拿引用计数
 * 句柄触发。CPU 回落到"仅预览"水平,Compose 重组不再抢线。
 *
 * 生命周期:
 *   - [start]:scope.launch(controller.startPreview 是 suspend)。幂等由 controller 保证。
 *   - [stop]:suspend,直接调 controller.stopPreview。
 *
 * IosAppHost.LaunchedEffect(sipState) 挂点**保持不动**——签名兼容 v1.2 keepalive API。
 */
object CameraSessionKeepalive {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 幂等启动 preview。scope.launch 因为 controller.startPreview 是 suspend。
     * IosAppHost 挂点 v1.2/v1.3 都不动,继续调用 [start] 传 config。
     */
    fun start(config: CaptureConfig) {
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_KEEPALIVE_START ${config.widthPx}x${config.heightPx}@${config.frameRate} " +
                "(delegating to IosCameraController)"
        )
        scope.launch {
            IosCameraController.startPreview(config)
        }
    }

    /**
     * suspend stop,直接调 controller。IosAppHost 挂点仍在 coroutine 内 await。
     */
    suspend fun stop() {
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_KEEPALIVE_STOP (delegating to IosCameraController)"
        )
        IosCameraController.stopPreview()
    }
}

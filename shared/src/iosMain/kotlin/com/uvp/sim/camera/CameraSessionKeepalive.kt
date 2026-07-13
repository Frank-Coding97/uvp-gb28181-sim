package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
     * cross-review R3 verify-retry #1:用 Mutex 串行 start/stop 整个"取消旧 job +
     * 赋值新 pendingStartJob"临界区。R3 v1 只在两语句之间保护 volatile field,
     * stop 仍可能在 cancel 与赋值之间插进来,先 join 后新 job 才启动预览 → 反转。
     * 同一 mutex 保护 start/stop 的整个状态转换。
     */
    private val lifecycleMutex = Mutex()
    private var pendingStartJob: kotlinx.coroutines.Job? = null

    /**
     * 幂等启动 preview + 应用最新 config。改造后 start 变 suspend,
     * IosAppHost 挂点在 LaunchedEffect 内本就是 coroutine 上下文,加 keyword 即可。
     *
     * cross-review R2 verify-retry #1:先 applyRuntimeConfig,让 config 变化通过
     * IosAppHost.LaunchedEffect 触发 keepalive.start 时,若 session 已运行会把新
     * 分辨率 / 帧率 / 码率 / GOP / codec / 朝向应用到活跃 session。
     */
    suspend fun start(config: CaptureConfig): Unit = lifecycleMutex.withLock {
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_KEEPALIVE_START ${config.widthPx}x${config.heightPx}@${config.frameRate} " +
                "(delegating to IosCameraController)"
        )
        IosCameraController.applyRuntimeConfig(config)
        pendingStartJob?.cancel()
        pendingStartJob = scope.launch {
            IosCameraController.startPreview(config)
        }
    }

    suspend fun stop(): Unit = lifecycleMutex.withLock {
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_KEEPALIVE_STOP (delegating to IosCameraController)"
        )
        pendingStartJob?.let {
            it.cancel()
            it.join()
        }
        pendingStartJob = null
        IosCameraController.stopPreview()
    }
}

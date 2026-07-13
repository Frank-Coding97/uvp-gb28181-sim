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
     * cross-review R3 #1:保存最近一次未完成的 startPreview job,让 [stop] 能取消它,
     * 避免 SIP 注销 → keepalive.stop 已跑完但 pending start 后启动摄像头,导致注销后
     * 相机仍在运行的生命周期反转。Volatile 因跨线程读写。
     */
    @kotlin.concurrent.Volatile
    private var pendingStartJob: kotlinx.coroutines.Job? = null

    /**
     * 幂等启动 preview + 应用最新 config。scope.launch 因为 controller.startPreview 是 suspend。
     * IosAppHost 挂点 v1.2/v1.3 都不动,继续调用 [start] 传 config。
     *
     * cross-review R2 verify-retry #1:先 applyRuntimeConfig(fire-and-forget,幂等),
     * 让 config 变化通过 IosAppHost.LaunchedEffect 触发 keepalive.start 时,若 session
     * 已运行会把新分辨率 / 帧率 / 码率 / GOP / codec / 朝向应用到活跃 session;若尚未
     * 启动,controller 内部把 config 缓存好等下一次 startPreview。这样 startPreview
     * 的 no-op 分支不再吞掉 config 更新。
     */
    fun start(config: CaptureConfig) {
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_KEEPALIVE_START ${config.widthPx}x${config.heightPx}@${config.frameRate} " +
                "(delegating to IosCameraController)"
        )
        IosCameraController.applyRuntimeConfig(config)
        // R3 #1:取消上一次未跑完的 start,再排新的 → 保证只有最新 config 的 job
        // 会真正启动;stop 也能取消最后这个 pending job。
        pendingStartJob?.cancel()
        pendingStartJob = scope.launch {
            IosCameraController.startPreview(config)
        }
    }

    /**
     * suspend stop,取消 pending start(如果还在排队)再调 controller stopPreview,
     * 避免 stop 后延迟启动摄像头的生命周期反转。
     */
    suspend fun stop() {
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_KEEPALIVE_STOP (delegating to IosCameraController)"
        )
        // cross-review R3 #1:先取 cancel pending start job 并 join,
        // 保证不会在 stopPreview 之后被 pending job 抢先启动会话。
        pendingStartJob?.let {
            it.cancel()
            it.join()
        }
        pendingStartJob = null
        IosCameraController.stopPreview()
    }
}

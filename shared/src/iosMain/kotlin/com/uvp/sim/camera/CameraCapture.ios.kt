package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.media.H264Frame
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
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
actual class CameraCapture actual constructor(config: CaptureConfig) {

    @Volatile
    private var config: CaptureConfig = config

    @Volatile
    private var handle: EncodingHandle? = null

    /**
     * Legacy hook — v1.2 tests 用它注入 pre-built streamer。v1.3-A 后 no-op(controller 独立管
     * 相机 lifecycle),留签名兼容避免测试引用点编译红。
     */
    @Suppress("UNUSED_PARAMETER")
    fun setStreamer(streamer: Any?) {
        // T-P6-1:IosCameraStreamer class 已删,参数类型退化为 Any?。仍保 no-op 签名兼容 v1.2 test 引用点。
        SystemLogger.emit(
            LogLevel.Debug, LogTag.Media,
            "CameraCapture.setStreamer no-op (v1.3-A T-P6-1: controller manages camera lifecycle)"
        )
    }

    internal fun applyConfig(config: CaptureConfig) {
        this.config = config
        pendingFacing = config.cameraFacing
        IosCameraController.applyRuntimeConfig(config)
    }

    internal fun configuredConfigForTest(): CaptureConfig = config

    internal fun pendingFacingForTest(): CameraFacing = pendingFacing

    /**
     * Fix #4:改成 flow builder 让 collect 起手先阻塞等 preview 就位或 fail。
     *
     * 之前 fire-and-forget `scope.launch { startPreview }` + 立即 requestEncoding,
     * preview 启动失败时 caller collect frames flow 永远收不到帧也不会 complete,
     * 表现为 "InviteMediaPipeline 挂死界面卡住无报错"。
     *
     * 现在:
     *   1. collect 起手 suspend 到 controller.startPreview 返回(内部 mutex 序列化 + startRunning 已完成)
     *   2. 检查 session.value 是否非空 → preview 起来 → 继续 requestEncoding
     *   3. preview fail → return@flow → flow 立即 complete → caller 拿到 "无数据流结束" 信号,可判 fail
     *   4. onCompletion 里 close handle,cancel 也走这条(caller 提前退出 collect)。
     *
     * 语义变化: start() 返回的 flow 从 "hot broadcast" 变 "cold flow",第一次 collect 才触发
     * preview 启动。Multiple collect 会各自触发一次 startPreview(幂等)+ 各自持一个 handle。
     */
    actual fun start(): Flow<H264Frame> = flow {
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "CameraCapture.start collecting ${config.widthPx}x${config.heightPx}@${config.frameRate}"
        )

        // 1. 阻塞等 preview 起来(startPreview 已经在 v1.3-cross-fix-#3 里 withContext startRunning)
        IosCameraController.startPreview(config)

        // 2. preview 起动失败(无 back camera / Simulator / device 占用)→ 抛异常触发 onMediaFailure
        //    (CodeX verify 后追加:改从 return@flow 静默 complete 为 exception,让 SIP 状态机
        //    能 cleanup,不再卡在 InCall 零帧)
        if (IosCameraController.session.value == null) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "CameraCapture.start: preview failed to start (Fix #4 追加:抛异常触发 onMediaFailure)"
            )
            throw IllegalStateException(
                "CameraCapture.start: preview failed to start (no back camera or device busy)"
            )
        }

        // 3. stash config + requestEncoding
        IosCameraController.stashConfigForEncoding(config)
        val h = IosCameraController.requestEncoding()
        handle = h
        // Fix #4 追加(CodeX verify):encoding 启动失败 → 抛异常让 InviteMediaPipeline.launchVideoSendLoop
        // 走 catch 分支 → onMediaFailure → SIP cleanup,避免静默 empty completion 让 SIP 卡在 InCall 零帧。
        if (h === NoOpEncodingHandle) {
            throw IllegalStateException(
                "CameraCapture.start: encoding failed to activate (no config or VT create returned null)"
            )
        }
        emitAll(h.frames)
    }.onCompletion {
        // 4. caller cancel 或 flow 自然结束 → close handle 释放 encoding refcount
        //    close 幂等,跟 stop() 的显式 close 双重触发也不会出错。
        handle?.close()
        handle = null
        SystemLogger.emit(
            LogLevel.Debug, LogTag.Media,
            "CameraCapture.start flow onCompletion handle closed"
        )
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
     * 双真实通道(dual-camera-channel):把切换意图同步落到 [IosCameraController.switchFacing],
     * 由 controller 在 sessionQueue 上重新 addInput 新朝向的 AVCaptureDeviceInput。
     *
     * Simulator / 缺前摄的机型:controller 内 lookupDeviceForFacing 返回 null → 保留旧 input +
     * 回滚 currentFacing,不影响正在跑的 session。跟 Android 侧同款 fire-and-forget 语义。
     */
    actual fun setFacing(facing: CameraFacing) {
        pendingFacing = facing
        config = config.copy(cameraFacing = facing)
        IosCameraController.switchFacing(facing)
    }
}

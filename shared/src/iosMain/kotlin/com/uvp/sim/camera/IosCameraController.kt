package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVImageBufferRef
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

/**
 * iOS 相机控制器 — v1.3-A 单一相机拥有者(preview-only 骨架阶段)。
 *
 * 设计目标:替代 v1.2 [IosCameraStreamer.stream] callbackFlow "采集 + 编码耦合" 的架构,
 * 拆两条独立路径:
 *   - **PreviewOnly**(本 task P1-1):只启 AVCaptureSession + 单一 delegate,不涉及 VT session
 *   - **StreamWithEncoding**(P2-1/P2-2 补):在 PreviewOnly 之上加挂 VTCompressionSession,
 *     通过 [EncodingHandle] 引用计数解耦 INVITE / 录像多路消费方
 *
 * 本文件(P1-1)只实现 PreviewOnly 骨架 —— encoding API 暂放 stub。
 *
 * 兼容策略:v1.2 [IosCameraStreamer.stream] 保留不动,直到 P6-1 才清理。P3/P4/P5 消费方
 * 逐个切换到 controller,期间新旧路径并存不冲突(单进程 back camera 只能被一路持有,
 * 靠 keepalive/AppEngine 生命周期错峰,与 v1.2 现状同款)。
 *
 * 参考 plan 未决问题结论:
 *   - Q1: 单 AVCaptureSession 复用(VT 后续动态挂 output,本文件不实现)
 *   - Q4: latestFramePixelBuffer 保留,delegate 每帧 publish 供 SnapshotCapture
 *   - Q5: keepalive 触发信号仍 SipState.Registered(挂点在 IosAppHost,不改)
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
object IosCameraController {

    private val mutex = Mutex()

    // ---- Public observable state ----

    private val _session = MutableStateFlow<AVCaptureSession?>(null)
    /** 当前 preview session,供 [PlatformCameraPreview] 挂 AVCaptureVideoPreviewLayer。 */
    val session: StateFlow<AVCaptureSession?> = _session.asStateFlow()

    private val _encodingActive = MutableStateFlow(false)
    /** encoding 是否在跑;P2-1 之前恒 false,便于 UI / 日志观测。 */
    val encodingActive: StateFlow<Boolean> = _encodingActive.asStateFlow()

    // ---- Internal state (guarded by mutex where suspend, @Volatile otherwise) ----

    @Volatile
    private var captureSession: AVCaptureSession? = null

    @Volatile
    private var captureInput: AVCaptureDeviceInput? = null

    @Volatile
    private var captureOutput: AVCaptureVideoDataOutput? = null

    @Volatile
    private var sampleDelegate: CameraSampleDelegate? = null

    /**
     * 最近一帧 CVImageBufferRef(delegate 每帧原子替换,旧值 CFRelease)。
     * 生命周期同 v1.2 [IosCameraStreamer.latestFrame],语义完全对齐。
     */
    @Volatile
    private var latestFrame: CVImageBufferRef? = null

    // =========================================================
    // External session mirror (bridge for v1.2 IosCameraStreamer coexistence)
    // =========================================================

    /**
     * v1.2 兼容桥:v1.2 [IosCameraStreamer.wireCaptureSession] 建 session 后调
     * [IosCameraSessionHolder.publish],holder 内部转发到本方法,让 controller.session
     * 也能反映 v1.2 路径的 session。消费点([PlatformCameraPreview])统一订阅 controller.session,
     * 新旧路径都能上画。
     *
     * 保护:controller 自己在跑 preview 时忽略外部 publish(避免覆盖真身)。P6-1 清理
     * v1.2 stream() 后本方法可以删除。
     */
    internal fun publishExternalSession(session: AVCaptureSession?) {
        if (captureSession != null) {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_EXTERNAL_PUBLISH_IGNORED controller_owns_session"
            )
            return
        }
        _session.value = session
    }

    // =========================================================
    // Preview API
    // =========================================================

    /**
     * 幂等启动 preview。已在运行返回 no-op。
     *
     * 内部动作:
     *   1. build AVCaptureSession + back camera input
     *   2. wire CameraSampleDelegate(单一 delegate,publish latest frame;encoding 部分留 P2)
     *   3. 挂 output,startRunning
     *   4. publish session 到 [session] StateFlow
     */
    suspend fun startPreview(config: CaptureConfig) = mutex.withLock {
        if (captureSession != null) {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_PREVIEW_ALREADY_RUNNING no-op"
            )
            return@withLock
        }
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_CONTROLLER_PREVIEW_START ${config.widthPx}x${config.heightPx}@${config.frameRate}"
        )
        val wired = wireCaptureSession(config)
        if (!wired) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_PREVIEW_START_FAIL"
            )
            // wireCaptureSession 已 emit 具体错误
        }
    }

    /**
     * 停止 preview,释放 session + delegate + latest frame。幂等(未运行 no-op)。
     */
    suspend fun stopPreview() = mutex.withLock {
        if (captureSession == null) {
            return@withLock
        }
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_CAMERA_CONTROLLER_PREVIEW_STOP")
        releaseInternal()
    }

    /**
     * 取当前最近一帧 pixel buffer,并额外 CFRetain 一次交给调用方。
     * 调用方使用完必须 [CFRelease]。返回 null 表示尚未有帧到达。
     */
    fun latestFramePixelBuffer(): CVImageBufferRef? {
        val current = latestFrame ?: return null
        CFRetain(current)
        return current
    }

    // =========================================================
    // Encoding API (P2-1 补,当前 stub)
    // =========================================================

    /**
     * P2-1 会实现:引用计数首次触发 create VTCompressionSession,末次 close 时 invalidate。
     * 当前 stub:直接抛 NotImplementedError,提醒调用方 P1-1 阶段尚未 wire encoding。
     */
    @Suppress("unused")
    fun requestEncoding(): EncodingHandle {
        throw NotImplementedError("EncodingHandle 由 T-P2-1 实现;当前 P1-1 只做 preview 骨架")
    }

    /**
     * P2-2 会实现:置 pendingForceKey 让下一个 encode 走 force-key 路径。
     * 当前 encoding 未启,no-op + 日志。
     */
    fun requestKeyFrame() {
        if (!_encodingActive.value) return
        // P2-2 补:pendingForceKey = true
    }

    // =========================================================
    // Delegate → per-frame processing
    // =========================================================

    /**
     * delegate 每帧回调。P1-1 阶段:仅 publish latest frame(供 SnapshotCapture)。
     * P2-2 补:若 encodingActive 则同时 encodeSample(sample, forceKey)。
     */
    private fun onSample(sample: CMSampleBufferRef) {
        val imageBuffer = CMSampleBufferGetImageBuffer(sample) ?: return
        publishLatestFrame(imageBuffer)
        // P2-2 hook 位置:if (_encodingActive.value) encodingSession?.encodeSample(sample, ...)
    }

    private fun publishLatestFrame(newFrame: CVImageBufferRef) {
        val old = latestFrame
        CFRetain(newFrame)
        latestFrame = newFrame
        if (old != null) CFRelease(old)
    }

    // =========================================================
    // Internals: build AVCaptureSession
    // =========================================================

    private fun wireCaptureSession(config: CaptureConfig): Boolean {
        val builtInWideAngle = AVCaptureDeviceTypeBuiltInWideAngleCamera
            ?: run {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "AVCaptureDeviceTypeBuiltInWideAngleCamera constant null - SDK mismatch"
                )
                return false
            }
        val device: AVCaptureDevice? = AVCaptureDevice.defaultDeviceWithDeviceType(
            deviceType = builtInWideAngle,
            mediaType = AVMediaTypeVideo,
            position = AVCaptureDevicePositionBack,
        )
        if (device == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "AVCaptureDevice back-camera lookup returned null (Simulator or restricted?)"
            )
            return false
        }

        val input: AVCaptureDeviceInput = memScoped {
            val errPtr = alloc<ObjCObjectVar<NSError?>>()
            val created = AVCaptureDeviceInput.deviceInputWithDevice(device, errPtr.ptr)
            if (created == null) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "AVCaptureDeviceInput init failed: ${errPtr.value?.localizedDescription ?: "unknown"}"
                )
                return@wireCaptureSession false
            }
            created
        }

        val session = AVCaptureSession()
        session.beginConfiguration()
        session.sessionPreset = pickSessionPreset(config.widthPx, config.heightPx)

        if (!session.canAddInput(input)) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "AVCaptureSession refused input - camera busy or restricted"
            )
            session.commitConfiguration()
            return false
        }
        session.addInput(input)

        val output = AVCaptureVideoDataOutput()
        val pixelFormatKey = kCVPixelBufferPixelFormatTypeKey ?: run {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "kCVPixelBufferPixelFormatTypeKey null - cannot configure output"
            )
            session.commitConfiguration()
            return false
        }
        val nv12 = NSNumber(unsignedInt = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
        output.videoSettings = mapOf<Any?, Any?>(pixelFormatKey to nv12)

        val delegate = CameraSampleDelegate { sample -> onSample(sample) }
        val queue = dispatch_queue_create("uvp.camera.controller", null)
        output.setSampleBufferDelegate(delegate, queue)

        if (!session.canAddOutput(output)) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "AVCaptureSession refused output - configuration incompatible"
            )
            session.commitConfiguration()
            return false
        }
        session.addOutput(output)
        session.commitConfiguration()

        // startRunning 是 blocking,dispatch 到后台 queue 避免阻塞调用者(与 v1.2 IosCameraStreamer 同款)
        dispatch_queue_create("uvp.camera.controller.start", null).let { startQueue ->
            dispatch_async(startQueue) { session.startRunning() }
        }

        captureSession = session
        captureInput = input
        captureOutput = output
        sampleDelegate = delegate
        _session.value = session
        return true
    }

    private fun pickSessionPreset(width: Int, height: Int): String {
        val fallback = AVCaptureSessionPreset1280x720 ?: return "AVCaptureSessionPreset1280x720"
        val chosen: String? = when {
            width >= 1920 || height >= 1080 -> AVCaptureSessionPreset1920x1080
            width >= 1280 || height >= 720 -> AVCaptureSessionPreset1280x720
            width >= 640 || height >= 480 -> AVCaptureSessionPreset640x480
            else -> AVCaptureSessionPreset1280x720
        }
        return chosen ?: fallback
    }

    private fun releaseInternal() {
        captureSession?.let { s ->
            if (s.isRunning()) s.stopRunning()
            captureInput?.let { s.removeInput(it) }
            captureOutput?.let { s.removeOutput(it) }
        }
        captureSession = null
        captureInput = null
        captureOutput = null
        sampleDelegate = null
        _session.value = null

        latestFrame?.let { CFRelease(it) }
        latestFrame = null
    }
}

/**
 * v1.3-A encoding path 引用计数句柄。P2-1 会实现真语义,P1-1 阶段仅定义接口。
 *
 * 语义:controller.[IosCameraController.requestEncoding] 每次返回一个 handle,refCount++。
 * [close] refCount--,归零时 controller invalidate VTCompressionSession。
 *
 * stale handle 语义:controller.stopPreview 强制 refCount 归 0 时,已发出但未 close 的
 * handle 变 no-op(内部持 generation counter,close 时校验)。
 */
interface EncodingHandle {
    /** encoding 帧流。encoding 结束(所有 handle close)后 completes。P2-2 补 SharedFlow 实现。 */
    val frames: kotlinx.coroutines.flow.Flow<com.uvp.sim.media.H264Frame>

    /** 幂等 close。stale handle no-op。 */
    fun close()
}

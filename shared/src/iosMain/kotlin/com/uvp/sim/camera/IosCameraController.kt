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
import com.uvp.sim.media.H264Frame
import com.uvp.sim.recording.IosRecordingFrameBridge
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /**
     * Fix #6:latestFrame publish 是"常驻税"(每帧 CFRetain/CFRelease),但只有 SnapshotCapture
     * 需要。用 subscribers 引用计数,只有 > 0 时 onSample 才 publishLatestFrame。
     * PreviewOnly + 无抓拍请求时,onSample 走轻路径(仅可能的 encode 分支),不动 latest。
     */
    @Volatile
    private var snapshotSubscribers: Int = 0

    /**
     * T-P2-2:当前 preview config 快照,requestEncoding 首次触发 EncodingSession 时
     * 用它构造(width / height / frameRate / codec)。startPreview 时保存,releaseInternal 清。
     * null 表示 preview 未启,此时 requestEncoding 无法构造 VT session。
     */
    @Volatile
    private var currentConfig: CaptureConfig? = null

    /**
     * T-P2-2:VTCompressionSession 生命周期封装。首次 requestEncoding 时 create,末次 close
     * (或 forceEncodingReset)时 invalidate。SPS/PPS + AVCC→Annex-B split 都在里面。
     */
    @Volatile
    private var encodingSession: EncodingSession? = null

    /**
     * T-P2-2:force-key 请求 pending 标志。requestKeyFrame 置 true,delegate.onSample 下一帧
     * consume 并清零。VideoToolbox force-key 属性只在 encodeSample 传入 frameProperties 才生效。
     */
    @Volatile
    private var pendingForceKey: Boolean = false

    /**
     * T-P2-2:frames 广播 SharedFlow。EncodingSession 产 H264Frame 后回调本 controller,
     * 一路 tryEmit 到 _frames(所有 EncodingHandle.frames 订阅者共享),一路 forward 到
     * [IosRecordingFrameBridge](保 v1.2 recording sink 语义)。
     *
     * replay=0 避免旧帧回放;extraBufferCapacity=64 匹配 v1.2 IosCameraStreamer 的 Channel 容量;
     * DROP_OLDEST 消费不上时丢老帧不阻塞 encode 线程。
     */
    private val _frames = MutableSharedFlow<H264Frame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val framesFlow: SharedFlow<H264Frame> = _frames.asSharedFlow()

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
    /**
     * T-P4-1 兜底 API:CameraCapture.start 极端 race 下 preview 尚未 launch 完 startPreview,
     * currentConfig 可能为 null。这里 stash config 保 requestEncoding 有 config 可用。
     * 已有 currentConfig 时不覆盖(避免 config 漂移)。
     */
    internal fun stashConfigForEncoding(config: CaptureConfig) {
        if (currentConfig == null) {
            currentConfig = config
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_CONFIG_STASHED via CameraCapture (preview not yet running)"
            )
        }
    }

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
        if (wired) {
            currentConfig = config  // T-P2-2:保存供 requestEncoding 构造 EncodingSession
            // Fix #3:startRunning 是 blocking,在 IO 上下文同步等它完成再释放 mutex。
            // 这样 stopPreview 进 mutex 时 session 已经真的 running,不会跟 pending start 抢。
            val sessionToStart = captureSession
            if (sessionToStart != null) {
                withContext(Dispatchers.Default) {
                    sessionToStart.startRunning()
                }
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "IOS_CAMERA_CONTROLLER_PREVIEW_RUNNING"
                )
            }
        } else {
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
        val session = captureSession ?: return@withLock
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_CAMERA_CONTROLLER_PREVIEW_STOP")
        // Fix #3:stopRunning 也 blocking,在 IO 上下文同步等它完成,防止 releaseInternal 后
        // AVCaptureSession 内部还有 async work 引用被释放对象。
        if (session.isRunning()) {
            withContext(Dispatchers.Default) {
                session.stopRunning()
            }
        }
        releaseInternal()
    }

    /**
     * 取当前最近一帧 pixel buffer,并额外 CFRetain 一次交给调用方。
     * 调用方使用完必须 [CFRelease]。返回 null 表示尚未有帧到达 或 snapshot 未订阅。
     *
     * Fix #6:必须先 [beginSnapshotCapture] 让 onSample 开始 publish latestFrame,
     * 否则总是 null。SnapshotCapture 用 begin/end 包裹调用。
     */
    fun latestFramePixelBuffer(): CVImageBufferRef? {
        val current = latestFrame ?: return null
        CFRetain(current)
        return current
    }

    /**
     * Fix #6:开始订阅 latestFrame publish。SnapshotCapture 在 takeJpeg 起手调,完成后 end。
     * 引用计数支持并发多个 SnapshotCapture 请求。
     */
    fun beginSnapshotCapture() {
        snapshotSubscribers += 1
    }

    /**
     * Fix #6:结束订阅。归零时 onSample 不再 publish latestFrame。
     * 清理 latestFrame 以释放最后引用(下次 begin 再从 delegate 首帧填)。
     */
    fun endSnapshotCapture() {
        snapshotSubscribers = maxOf(0, snapshotSubscribers - 1)
        if (snapshotSubscribers == 0) {
            latestFrame?.let { CFRelease(it) }
            latestFrame = null
        }
    }

    // =========================================================
    // Encoding API — T-P2-1 refCount + generation counter (fake encoding lifecycle)
    // =========================================================

    /**
     * 并发保护:iOS 侧 requestEncoding / handle.close 通常在 main thread 或 coroutine
     * dispatch 内调用,当前 @Volatile Int 假设"实际调用点不真并发"。T-P2-2 加真 VT session
     * 时如果发现调用点跨线程,升级到 AtomicInt / Mutex。
     */
    @Volatile
    private var encodingRefCount: Int = 0

    /**
     * generation counter — 每次 stopPreview 强制归零时递增,让已发出但未 close 的 handle
     * 通过 generation mismatch 变 no-op(stale handle 语义,plan 2.5 节)。
     */
    @Volatile
    private var encodingGeneration: Int = 0

    /**
     * P2-1:引用计数首次触发 fake encoding start,末次 close fake encoding stop。
     * 真 VTCompressionSession lifecycle 由 T-P2-2 补(在本方法内部加 EncodingSession create
     * + invalidate 分支)。当前 fake:仅更新 [encodingActive] StateFlow 便于测试观测。
     *
     * 每次调用返回一个新 handle,不同 handle 共享同一份 encoding lifecycle。
     */
    fun requestEncoding(): EncodingHandle {
        val gen = encodingGeneration
        encodingRefCount += 1
        val newCount = encodingRefCount
        if (newCount == 1) {
            // T-P2-2:首次触发真 VTCompressionSession create。config 从 preview 阶段保存。
            val cfg = currentConfig
            if (cfg == null) {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "IOS_CAMERA_CONTROLLER_ENCODING_START_NO_CONFIG preview 未启,encoding 无 config"
                )
                // Fix #1:rollback refCount 并返回 NoOp handle。之前返回绑定 gen 的
                // EncodingHandleImpl 会让 handle.close 通过 generation 匹配把 refCount 减到 -1。
                encodingRefCount = 0
                return NoOpEncodingHandle
            }
            val session = EncodingSession(cfg) { frame ->
                _frames.tryEmit(frame)
                IosRecordingFrameBridge.onVideoFrame(frame)  // 保 v1.2 recording sink 语义
            }
            if (!session.start()) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_CAMERA_CONTROLLER_ENCODING_START_FAIL VT create failed"
                )
                encodingRefCount = 0  // Fix #1:同上 rollback + NoOp handle
                return NoOpEncodingHandle
            }
            encodingSession = session
            _encodingActive.value = true
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_ENCODING_START gen=$gen refCount=1 VT session live"
            )
        } else {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_ENCODING_REUSE gen=$gen refCount=$newCount"
            )
        }
        return EncodingHandleImpl(generation = gen)
    }

    /**
     * P2-2 会补:置 pendingForceKey 让下一个 encode 走 force-key 路径。
     * 当前 encoding 未真启,no-op + 日志。
     */
    fun requestKeyFrame() {
        if (!_encodingActive.value) return
        pendingForceKey = true
        SystemLogger.emit(
            LogLevel.Debug, LogTag.Media,
            "IOS_CAMERA_CONTROLLER_REQUEST_KEYFRAME pendingForceKey=true"
        )
    }

    /**
     * handle.close 内部调用点。校验 generation 一致才 decrement refCount。
     * generation mismatch 走 no-op(stopPreview 强制归零后的 stale handle)。
     */
    private fun closeHandleInternal(handleGeneration: Int) {
        if (handleGeneration != encodingGeneration) {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_HANDLE_STALE_CLOSE handle_gen=$handleGeneration current_gen=$encodingGeneration"
            )
            return
        }
        // Fix #1 defense-in-depth:refCount clamp 到 0 底,防任何路径漂移到负数
        encodingRefCount = maxOf(0, encodingRefCount - 1)
        val newCount = encodingRefCount
        if (newCount == 0) {
            // T-P2-2:末次 close,真 invalidate VT session。plan Q6 完全释放,不 pause。
            encodingSession?.invalidate()
            encodingSession = null
            pendingForceKey = false
            _encodingActive.value = false
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_ENCODING_STOP gen=$handleGeneration VT invalidated"
            )
        } else {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_ENCODING_RELEASE_ONE gen=$handleGeneration refCount=$newCount"
            )
        }
    }

    /**
     * stopPreview 强制归零:递增 generation 让所有旧 handle 变 stale,清 refCount。
     * 由 [releaseInternal] 调用。
     */
    private fun forceEncodingReset() {
        if (encodingRefCount > 0 || _encodingActive.value || encodingSession != null) {
            // T-P2-2:真 invalidate VT session
            encodingSession?.invalidate()
            encodingSession = null
            pendingForceKey = false

            encodingGeneration += 1
            encodingRefCount = 0
            _encodingActive.value = false
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_ENCODING_FORCE_RESET new_gen=$encodingGeneration"
            )
        }
    }

    /**
     * [EncodingHandle] 具体实现。持 generation 快照,close 时通过 controller 校验。
     */
    private class EncodingHandleImpl(private val generation: Int) : EncodingHandle {
        // T-P2-2:frames = controller 的 SharedFlow<H264Frame> 广播,所有 handle 共享同一份。
        // encoding 结束(refCount 归 0 或 stopPreview 强制归零)后 SharedFlow 不再 emit;
        // 订阅者调用点通过 handle.close 触发 controller 释放语义,自身 collect 应配合 handle
        // 生命周期(见 CameraCapture.ios / IosRecordingService 消费点)。
        override val frames: kotlinx.coroutines.flow.Flow<H264Frame> = framesFlow

        @Volatile
        private var closed: Boolean = false

        override fun close() {
            if (closed) return
            closed = true
            closeHandleInternal(generation)
        }
    }

    // =========================================================
    // Delegate → per-frame processing
    // =========================================================

    /**
     * delegate 每帧回调。P1-1 阶段:仅 publish latest frame(供 SnapshotCapture)。
     * P2-2 补:若 encodingActive 则同时 encodeSample(sample, forceKey)。
     */
    private fun onSample(sample: CMSampleBufferRef) {
        // Fix #6:PreviewOnly + 无 snapshot 请求时 quick exit,不做 CFRetain/CFRelease 常驻税
        val needLatest = snapshotSubscribers > 0
        val needEncode = _encodingActive.value
        if (!needLatest && !needEncode) return

        if (needLatest) {
            val imageBuffer = CMSampleBufferGetImageBuffer(sample) ?: return
            publishLatestFrame(imageBuffer)
        }
        // T-P2-2:encoding active 时 encode 单帧,forceKey 从 pendingForceKey 消费一次
        if (needEncode) {
            val force = pendingForceKey
            if (force) pendingForceKey = false
            encodingSession?.encodeSample(sample, forceKey = force)
        }
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

        // T-P2-2 cross-review fix #3:startRunning 从 fire-and-forget dispatch_async 改成由
        // startPreview 内 withContext(Dispatchers.Default) 阻塞等成 running,让 mutex 一直握到
        // session 真的 running,避免 "start 排队未执行 → stop 释放 session → 排队的 start 稍后
        // 拉起已释放 session" 抖动导致回主页卡顿。
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
        // T-P2-1/2 stale handle 语义 + 真 VT invalidate 已在 forceEncodingReset 内联
        forceEncodingReset()

        captureSession?.let { s ->
            // Fix #3:stopRunning 已由 stopPreview 在 withContext(Default) 内跑,这里只做 input/output 清理
            captureInput?.let { s.removeInput(it) }
            captureOutput?.let { s.removeOutput(it) }
        }
        captureSession = null
        captureInput = null
        captureOutput = null
        sampleDelegate = null
        _session.value = null
        currentConfig = null

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

/**
 * Fix #1/#4 no-op handle for encoding failure paths(no config / VT create fail).
 * frames 立即完成的 emptyFlow → 消费方 collect 拿到 no data 且 flow completes,
 * 不会挂死。close 完全 no-op,不进 closeHandleInternal 避免 refCount 下溢。
 *
 * 提升为 file top-level internal object 便于消费方(CameraCapture)identity check(`h === NoOpEncodingHandle`)
 * 从而把"编码启动失败"转成 flow 里的 exception,让 InviteMediaPipeline.launchVideoSendLoop 走 catch 分支
 * → onMediaFailure → SIP 状态机 cleanup,而不是静默 empty completion(CodeX verify #4 追加发现)。
 */
internal object NoOpEncodingHandle : EncodingHandle {
    override val frames: kotlinx.coroutines.flow.Flow<com.uvp.sim.media.H264Frame> =
        kotlinx.coroutines.flow.emptyFlow()

    override fun close() { /* no-op — encoding never actually started */ }
}

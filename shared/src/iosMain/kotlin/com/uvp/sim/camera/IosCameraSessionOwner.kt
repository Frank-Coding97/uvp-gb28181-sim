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
import kotlin.concurrent.Volatile
import kotlin.coroutines.suspendCoroutine
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePosition
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVFrameRateRange
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

/**
 * cross-review R1 #4 拆分 step 3(from [IosCameraController]):
 * AVCaptureSession 硬件生命周期 + 运行期切换。
 *
 * 拥有:
 *  - sessionQueue(iOS 侧硬件调用要串行化)
 *  - AVCaptureSession / Input / Output / delegate / activeDevice 句柄
 *  - currentConfig / currentFacing(跟 session 硬绑定,单一真身在本 object)
 *  - session StateFlow(暴露给 UI 挂 preview layer)
 *
 * 不涉及:
 *  - encoding refCount / VT session lifecycle → [IosCameraEncodingCoordinator]
 *  - 每帧 pixel buffer / 采样统计 → [IosCameraFrameBuffer]
 *  - OSD flow 分发 / snapshot / preview overlay → 保留在 [IosCameraController] facade
 *
 * 消费方(CameraCapture / IosAppHost / PlatformCameraPreview)不直接引用本对象,
 * 继续走 [IosCameraController] facade。
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal object IosCameraSessionOwner {

    private val sessionQueue = dispatch_queue_create("uvp.camera.session", null)

    private val _session = MutableStateFlow<AVCaptureSession?>(null)
    /** 当前 preview session,供 [PlatformCameraPreview] 挂 AVCaptureVideoPreviewLayer。 */
    val session: StateFlow<AVCaptureSession?> = _session.asStateFlow()

    @Volatile
    private var captureSession: AVCaptureSession? = null

    @Volatile
    private var captureInput: AVCaptureDeviceInput? = null

    @Volatile
    private var captureOutput: AVCaptureVideoDataOutput? = null

    @Volatile
    private var sampleDelegate: CameraSampleDelegate? = null

    /**
     * 当前 AVCaptureSession 上挂着的物理设备(前置 或 后置)。
     * `switchFacing` / `applyRuntimeConfig` 需要复用它对活跃设备重新 lockForConfiguration
     * 调帧率,或直接 removeInput 换朝向。preview 未启时为 null。
     */
    @Volatile
    private var activeDevice: AVCaptureDevice? = null

    /**
     * 当前摄像头朝向。跟 Android [com.uvp.sim.camera.AndroidCameraStreamer.currentFacing]
     * 对齐:setFacing 同步更新此字段(观察者立刻可读),真正的 session 切换 dispatch 到
     * [sessionQueue] 异步执行。preview 未启时仍持"目标朝向",下一次 startPreview 应用。
     */
    @Volatile
    private var currentFacing: CameraFacing = CameraFacing.BACK

    /**
     * T-P2-2:当前 preview config 快照,requestEncoding 首次触发 EncodingSession 时
     * 用它构造(width / height / frameRate / codec)。startPreview 时保存,tearDown 清。
     * null 表示 preview 未启,此时 requestEncoding 无法构造 VT session。
     */
    @Volatile
    private var currentConfig: CaptureConfig? = null

    fun isSessionActive(): Boolean = captureSession != null
    fun currentFacing(): CameraFacing = currentFacing
    fun currentConfig(): CaptureConfig? = currentConfig
    fun setCurrentConfig(config: CaptureConfig) { currentConfig = config }
    fun setCurrentFacing(facing: CameraFacing) { currentFacing = facing }

    /**
     * T-P4-1 兜底 API:CameraCapture.start 极端 race 下 preview 尚未 launch 完 startPreview,
     * currentConfig 可能为 null。这里 stash config 保 requestEncoding 有 config 可用。
     * 已有 currentConfig 时不覆盖(避免 config 漂移)。
     */
    fun stashConfigForEncoding(config: CaptureConfig) {
        if (currentConfig == null) {
            currentConfig = config
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_CONFIG_STASHED via CameraCapture (preview not yet running)"
            )
        }
    }

    /**
     * v1.2 兼容桥:v1.2 [IosCameraStreamer.wireCaptureSession] 建 session 后调
     * [IosCameraSessionHolder.publish],holder 内部转发到本方法,让 controller.session
     * 也能反映 v1.2 路径的 session。消费点([PlatformCameraPreview])统一订阅 controller.session,
     * 新旧路径都能上画。
     *
     * 保护:controller 自己在跑 preview 时忽略外部 publish(避免覆盖真身)。P6-1 清理
     * v1.2 stream() 后本方法可以删除。
     */
    fun publishExternalSession(externalSession: AVCaptureSession?) {
        if (captureSession != null) {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_EXTERNAL_PUBLISH_IGNORED controller_owns_session"
            )
            return
        }
        _session.value = externalSession
    }

    /** helper — 把 block 派到 session queue 异步执行,suspend 直到完成。 */
    suspend fun <T> onSessionQueue(block: () -> T): T = suspendCoroutine { continuation ->
        dispatch_async(sessionQueue) {
            continuation.resumeWith(runCatching(block))
        }
    }

    /**
     * 把 [switchFacing] 请求派到 session queue(不 suspend,fire-and-forget)。
     * preview 未启时调用方应先 [setCurrentFacing] 记目标,不派本 dispatch。
     */
    fun dispatchSwitchFacing(target: CameraFacing) {
        dispatch_async(sessionQueue) {
            reconfigureFacingOnQueue(target)
        }
    }

    /**
     * 把 [applyRuntimeConfig] 请求派到 session queue。
     * @param onEncodingParamsChanged encoding-params 变化时调,让 encoding coordinator 重建 VT session。
     */
    fun dispatchReconfigure(
        old: CaptureConfig?,
        new: CaptureConfig,
        onEncodingParamsChanged: (CaptureConfig) -> Unit,
    ) {
        dispatch_async(sessionQueue) {
            reconfigureCaptureOnQueue(old = old, new = new, onEncodingParamsChanged = onEncodingParamsChanged)
        }
    }

    /**
     * 建 AVCaptureSession + input + output + delegate + startRunning。
     * 由调用方在 [onSessionQueue] 内调用。成功时 [captureSession] 等字段填充,[session] 发布。
     *
     * @param sampleCallback delegate 每帧回调(controller 转发到 frameBuffer / encoding coordinator)。
     * @return true 表示 wired & running。
     */
    fun wireOnQueue(config: CaptureConfig, sampleCallback: (CMSampleBufferRef) -> Unit): Boolean {
        val device = lookupDeviceForFacing(config.cameraFacing) ?: return false

        val input: AVCaptureDeviceInput = memScoped {
            val errPtr = alloc<ObjCObjectVar<NSError?>>()
            val created = AVCaptureDeviceInput.deviceInputWithDevice(device, errPtr.ptr)
            if (created == null) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "AVCaptureDeviceInput init failed: ${errPtr.value?.localizedDescription ?: "unknown"}"
                )
                return@wireOnQueue false
            }
            created
        }

        val output = AVCaptureVideoDataOutput()
        val pixelFormatKey = kCVPixelBufferPixelFormatTypeKey ?: run {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "kCVPixelBufferPixelFormatTypeKey null - cannot configure output"
            )
            return false
        }
        val nv12 = NSNumber(unsignedInt = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
        output.videoSettings = mapOf<Any?, Any?>(pixelFormatKey to nv12)

        val delegate = CameraSampleDelegate { sample -> sampleCallback(sample) }
        val queue = dispatch_queue_create("uvp.camera.controller", null)
        output.setSampleBufferDelegate(delegate, queue)

        val session = AVCaptureSession()
        val started = configureThenStartSession(
            beginConfiguration = { session.beginConfiguration() },
            configure = {
                session.sessionPreset = pickSessionPreset(config.widthPx, config.heightPx)
                if (!session.canAddInput(input)) {
                    SystemLogger.emit(
                        LogLevel.Error, LogTag.Media,
                        "AVCaptureSession refused input - camera busy or restricted"
                    )
                    false
                } else {
                    session.addInput(input)
                    configureCaptureFrameRate(device, config.frameRate)
                    if (!session.canAddOutput(output)) {
                        SystemLogger.emit(
                            LogLevel.Error, LogTag.Media,
                            "AVCaptureSession refused output - configuration incompatible"
                        )
                        false
                    } else {
                        session.addOutput(output)
                        output.connectionWithMediaType(AVMediaTypeVideo)?.let { connection ->
                            if (connection.isVideoOrientationSupported()) {
                                connection.setVideoOrientation(AVCaptureVideoOrientationPortrait)
                            }
                        }
                        true
                    }
                }
            },
            commitConfiguration = { session.commitConfiguration() },
            startRunning = { session.startRunning() },
        )
        if (!started) return false

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_OUTPUT_ORIENTATION Portrait source; encoding canvas=" +
                "${config.widthPx}x${config.heightPx}"
        )

        captureSession = session
        captureInput = input
        captureOutput = output
        sampleDelegate = delegate
        activeDevice = device
        _session.value = session
        return true
    }

    /**
     * 由调用方在 [onSessionQueue] 内调用。stopRunning + tear down 全部句柄。
     */
    fun tearDownOnQueue() {
        captureSession?.let { s ->
            if (s.isRunning()) s.stopRunning()
            s.beginConfiguration()
            try {
                captureInput?.let { s.removeInput(it) }
                captureOutput?.let { s.removeOutput(it) }
            } finally {
                s.commitConfiguration()
            }
        }
        captureSession = null
        captureInput = null
        captureOutput = null
        sampleDelegate = null
        activeDevice = null
        _session.value = null
        currentConfig = null
    }

    /**
     * 测试专用:把 preview / encoding 未跑场景下的可变字段(currentFacing / currentConfig)重置到默认。
     * 跨 test 隔离用。preview 若在跑必须先 stopPreview。
     */
    fun resetPendingStateForTest() {
        currentFacing = CameraFacing.BACK
        currentConfig = null
    }

    /**
     * 按朝向解析物理 [AVCaptureDevice]。SDK 常量缺失 / Simulator 无摄像头时返回 null 并 emit error log。
     */
    private fun lookupDeviceForFacing(facing: CameraFacing): AVCaptureDevice? {
        val builtInWideAngle = AVCaptureDeviceTypeBuiltInWideAngleCamera
        if (builtInWideAngle == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "AVCaptureDeviceTypeBuiltInWideAngleCamera constant null - SDK mismatch"
            )
            return null
        }
        val position: AVCaptureDevicePosition = when (facing) {
            CameraFacing.FRONT -> AVCaptureDevicePositionFront
            CameraFacing.BACK -> AVCaptureDevicePositionBack
        }
        val device = AVCaptureDevice.defaultDeviceWithDeviceType(
            deviceType = builtInWideAngle,
            mediaType = AVMediaTypeVideo,
            position = position,
        )
        if (device == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "AVCaptureDevice lookup returned null facing=$facing (Simulator or restricted?)"
            )
        }
        return device
    }

    /**
     * dispatched onto [sessionQueue]:把当前 [captureSession] 上挂着的 [captureInput] 换成新朝向的。
     * 失败(找不到新设备 / addInput 被拒)时保留旧 input 继续跑,并把 [currentFacing] 回滚到旧值。
     */
    private fun reconfigureFacingOnQueue(target: CameraFacing) {
        val session = captureSession ?: return
        val previousFacing = when (target) {
            CameraFacing.FRONT -> CameraFacing.BACK
            CameraFacing.BACK -> CameraFacing.FRONT
        }
        val oldInput = captureInput
        val newDevice = lookupDeviceForFacing(target) ?: run {
            currentFacing = previousFacing
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_SWITCH_FACING_FAIL target=$target device lookup null; keeping current input"
            )
            return
        }
        val newInput: AVCaptureDeviceInput = memScoped {
            val errPtr = alloc<ObjCObjectVar<NSError?>>()
            val created = AVCaptureDeviceInput.deviceInputWithDevice(newDevice, errPtr.ptr)
            if (created == null) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_CAMERA_CONTROLLER_SWITCH_FACING_INPUT_FAIL target=$target " +
                        (errPtr.value?.localizedDescription ?: "unknown")
                )
                currentFacing = previousFacing
                return@reconfigureFacingOnQueue
            }
            created
        }

        session.beginConfiguration()
        var committed = false
        try {
            if (oldInput != null) session.removeInput(oldInput)
            if (!session.canAddInput(newInput)) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_CAMERA_CONTROLLER_SWITCH_FACING_REJECTED target=$target session.canAddInput=false; restoring old"
                )
                if (oldInput != null) session.addInput(oldInput)
                currentFacing = previousFacing
                return
            }
            session.addInput(newInput)
            captureInput = newInput
            activeDevice = newDevice
            currentConfig?.let { configureCaptureFrameRate(newDevice, it.frameRate) }
            committed = true
        } finally {
            session.commitConfiguration()
        }
        if (committed) {
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_SWITCH_FACING_DONE facing=$target"
            )
        }
    }

    /**
     * dispatched onto [sessionQueue]:对比 [old] / [new] 差分:
     * - 分辨率 / preset:beginConfiguration + sessionPreset 换档
     * - 朝向:走 [reconfigureFacingOnQueue] 同款路径(inline 展开避免嵌套 dispatch)
     * - 帧率:复用 [activeDevice] 直接 lockForConfiguration 换 min/max frame duration
     * - encoding 参数 / codec:通过 [onEncodingParamsChanged] 回调 encoding coordinator
     */
    private fun reconfigureCaptureOnQueue(
        old: CaptureConfig?,
        new: CaptureConfig,
        onEncodingParamsChanged: (CaptureConfig) -> Unit,
    ) {
        val session = captureSession ?: return

        val facingChanged = old?.cameraFacing != new.cameraFacing
        val resolutionChanged = old == null || old.widthPx != new.widthPx || old.heightPx != new.heightPx
        val frameRateChanged = old == null || old.frameRate != new.frameRate
        val encodingParamsChanged = old == null ||
            old.bitrateBps != new.bitrateBps ||
            old.keyframeIntervalSeconds != new.keyframeIntervalSeconds ||
            old.videoCodec != new.videoCodec ||
            old.widthPx != new.widthPx ||
            old.heightPx != new.heightPx ||
            old.frameRate != new.frameRate

        if (resolutionChanged || frameRateChanged) {
            session.beginConfiguration()
            try {
                if (resolutionChanged) {
                    session.sessionPreset = pickSessionPreset(new.widthPx, new.heightPx)
                }
                if (frameRateChanged && !facingChanged) {
                    // facing 会顺带调 frame rate;避免重复 lockForConfiguration
                    activeDevice?.let { configureCaptureFrameRate(it, new.frameRate) }
                }
            } finally {
                session.commitConfiguration()
            }
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_APPLY_RUNTIME_CONFIG_SESSION " +
                    "${new.widthPx}x${new.heightPx}@${new.frameRate} preset=${pickSessionPreset(new.widthPx, new.heightPx)}"
            )
        }

        if (facingChanged) {
            reconfigureFacingOnQueue(new.cameraFacing)
        }

        if (encodingParamsChanged) {
            onEncodingParamsChanged(new)
        }
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

    private fun configureCaptureFrameRate(device: AVCaptureDevice, frameRate: Int) {
        val supported = device.activeFormat.videoSupportedFrameRateRanges.any { value ->
            val range = value as? AVFrameRateRange ?: return@any false
            frameRate.toDouble() >= range.minFrameRate && frameRate.toDouble() <= range.maxFrameRate
        }
        if (!supported) {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Media,
                "IOS_CAMERA_FRAME_RATE_UNSUPPORTED requested=$frameRate; using device default",
            )
            return
        }
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!device.lockForConfiguration(error.ptr)) {
                SystemLogger.emit(
                    LogLevel.Warning,
                    LogTag.Media,
                    "IOS_CAMERA_FRAME_RATE_LOCK_FAILED " +
                        (error.value?.localizedDescription ?: "unknown"),
                )
                return@memScoped
            }
            try {
                val duration = CMTimeMake(value = 1L, timescale = frameRate)
                device.activeVideoMinFrameDuration = duration
                device.activeVideoMaxFrameDuration = duration
            } finally {
                device.unlockForConfiguration()
            }
        }
    }
}

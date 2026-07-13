package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.config.OsdConfig
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeMake
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
    private val sessionQueue = dispatch_queue_create("uvp.camera.session", null)

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
     * cross-review R1 #4 拆分 step 1:latestFrame + snapshotSubscribers + sampleCount / lastSampleAtMs
     * 抽到 [IosCameraFrameBuffer]。本 object 保持 facade,委托到 buffer 对象。
     */

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

    private val defaultOsdConfigFlow: StateFlow<OsdConfig> =
        MutableStateFlow(OsdConfig()).asStateFlow()

    @Volatile
    private var osdConfigFlow: StateFlow<OsdConfig> = defaultOsdConfigFlow

    /**
     * T-P2-2:force-key 请求 pending 标志。requestKeyFrame 置 true,delegate.onSample 下一帧
     * consume 并清零。VideoToolbox force-key 属性只在 encodeSample 传入 frameProperties 才生效。
     */
    @Volatile
    private var pendingForceKey: Boolean = false

    /**
     * T-B1-6:HEVC 硬编能力启动时探测缓存。App 冷启一次,后续 SDP capability advertise
     * 层 (`PlatformVideoCapabilities.ios`) 读这个字段决定是否 offer H.265。
     *
     * 探测方式:尝试 `VTCompressionSessionCreate(kCMVideoCodecType_HEVC, 320x240)` +
     * 立即 invalidate。status == 0 表示硬件支持,否则 false。任何 crash / 异常都视为
     * 不支持(runCatching 兜底,R10 缓解)。
     *
     * 首次访问触发探测(lazy),之后走缓存。多线程首次并发访问可能触发两次探测,不影响
     * 结果一致性(idempotent 探测)。
     */
    @Volatile
    private var _hevcHwEncodeSupported: Boolean? = null

    /** T-B1-6:硬编 HEVC 支持结果,首次读时探测,后续走缓存。 */
    val hevcHwEncodeSupported: Boolean
        get() {
            _hevcHwEncodeSupported?.let { return it }
            val result = probeHevcHwEncode()
            _hevcHwEncodeSupported = result
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_HEVC_HW_ENCODE_PROBE supported=$result",
            )
            return result
        }

    /**
     * T-B1-6:实际的 HEVC 硬编探测。构建一个最小尺寸 VT session,create 成功则支持,
     * 立即 Invalidate 释放。整段 runCatching 包一层,exception → 不支持。
     */
    private fun probeHevcHwEncode(): Boolean = runCatching {
        HevcHwProbe.probe()
    }.getOrElse {
        SystemLogger.emit(
            LogLevel.Warning, LogTag.Media,
            "IOS_HEVC_HW_ENCODE_PROBE_EXCEPTION msg=${it.message} fallback=unsupported",
        )
        false
    }

    /**
     * 测试可注入的探测结果覆盖 —— 仅 iosTest 使用,让 `HevcHwEncodeProbeTest` 能覆盖
     * 硬件缺失场景。不做同步锁(测试单线程调用)。
     */
    internal fun overrideHevcHwEncodeSupportedForTest(value: Boolean?) {
        _hevcHwEncodeSupported = value
    }

    /** 测试专用:当前朝向(setFacing / applyRuntimeConfig 同步更新)。 */
    internal fun currentFacingForTest(): CameraFacing = currentFacing

    /** 测试专用:当前 config snapshot(applyRuntimeConfig 同步更新)。 */
    internal fun currentConfigForTest(): CaptureConfig? = currentConfig

    /**
     * 测试专用:把 preview / encoding 未跑场景下的可变字段(currentFacing / currentConfig)重置到
     * 默认。跨 test 隔离用。preview 若在跑必须先 stopPreview。
     */
    internal fun resetPendingStateForTest() {
        currentFacing = CameraFacing.BACK
        currentConfig = null
    }

    /** Install the host-owned OSD flow. Existing sessions observe its hot updates. */
    internal fun installOsdConfigFlow(flow: StateFlow<OsdConfig>) {
        if (osdConfigFlow === flow) return
        osdConfigFlow = flow
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_CONTROLLER_OSD_FLOW_INSTALLED",
        )
    }

    internal fun osdConfigFlowForEncoding(): StateFlow<OsdConfig> = osdConfigFlow

    /** Read-only OSD source for the native preview overlay. */
    val previewOsdConfigFlow: StateFlow<OsdConfig>
        get() = osdConfigFlow

    /** Current encoded canvas used to scale the native preview OSD. */
    fun previewOutputSize(): Pair<Int, Int> {
        val config = currentConfig
        return (config?.widthPx ?: 1280) to (config?.heightPx ?: 720)
    }

    internal fun osdConfigFlowForTest(): StateFlow<OsdConfig> = osdConfigFlow

    internal fun resetOsdConfigFlowForTest() {
        osdConfigFlow = defaultOsdConfigFlow
    }

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
        currentFacing = config.cameraFacing
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_CONTROLLER_PREVIEW_START ${config.widthPx}x${config.heightPx}@${config.frameRate} facing=${config.cameraFacing}"
        )
        val wired = onSessionQueue {
            wireAndStartCaptureSession(config)
        }
        if (wired) {
            currentConfig = config
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_PREVIEW_RUNNING facing=$currentFacing"
            )
        } else {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_PREVIEW_START_FAIL"
            )
            // wireCaptureSession 已 emit 具体错误
        }
    }

    /**
     * 运行期切换前 / 后置摄像头,fire-and-forget。跟 Android [com.uvp.sim.camera.AndroidCameraStreamer.setFacing]
     * 语义对齐:同值 no-op;不同值 → 更新 [currentFacing] 后 dispatch 到 [sessionQueue],
     * 若 session 已建则 beginConfiguration → removeInput → addInput(新朝向) → commitConfiguration。
     *
     * 不 tear down [encodingSession] —— VTCompressionSession 只关心像素 buffer,换朝向对它透明,
     * 已发出去的 [EncodingHandle.frames] 订阅者会无缝继续拿到新朝向的编码帧。
     *
     * Simulator 或缺前摄的机型:defaultDeviceWithDeviceType 返回 null → 保留旧 input,log warning。
     */
    fun switchFacing(facing: CameraFacing) {
        if (currentFacing == facing) return
        currentFacing = facing
        // preview 尚未启:仅记录目标,startPreview 时会用新值
        if (captureSession == null) {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_SWITCH_FACING_PENDING facing=$facing (preview 未启,startPreview 时应用)"
            )
            return
        }
        dispatch_async(sessionQueue) {
            reconfigureFacingOnQueue(facing)
        }
    }

    /**
     * 运行期改分辨率 / 帧率 / 码率 / GOP / codec / 朝向。跟 Android
     * [com.uvp.sim.camera.AndroidCameraStreamer.applyCaptureConfig] 对齐,fire-and-forget。
     *
     * 语义分层:
     * - 同值 short-circuit
     * - 更新 [currentConfig] 后 dispatch 到 [sessionQueue]:
     *   - 分辨率变 → session preset 换档 + 若 encoding active 则 VT session 重建(重建前 emit warning
     *     日志方便定位卡顿)
     *   - 朝向变 → removeInput / addInput
     *   - 帧率变 → 若朝向未变,复用 [activeDevice] 直接 lockForConfiguration 换 min/max frame duration
     *   - 码率 / GOP / codec 变 → 若 encoding active 则重建 VT session(inline VTSetProperty 只能改
     *     bitrate,codec / GOP 需要重建;为了行为一致这里一律重建)
     * - preview 未启:仅缓存,startPreview / requestEncoding 首触时会读到新值
     */
    fun applyRuntimeConfig(new: CaptureConfig) {
        val old = currentConfig
        if (old != null && old == new) return
        currentConfig = new
        currentFacing = new.cameraFacing
        // preview 未启:currentConfig 已更新,startPreview 时会用新值,requestEncoding 首触
        // 也会用最新 config 建 VT session。不 dispatch。
        if (captureSession == null) {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_APPLY_RUNTIME_CONFIG_PENDING preview 未启,配置已更新等下次 startPreview"
            )
            return
        }
        dispatch_async(sessionQueue) {
            reconfigureCaptureOnQueue(old = old, new = new)
        }
    }

    /**
     * 停止 preview,释放 session + delegate + latest frame。幂等(未运行 no-op)。
     */
    suspend fun stopPreview() = mutex.withLock {
        val session = captureSession
        if (session == null) {
            // Encoding handles may be reserved before preview is wired. They still
            // need the same generation invalidation semantics as a live session.
            forceEncodingReset()
            return@withLock
        }
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_CAMERA_CONTROLLER_PREVIEW_STOP")
        onSessionQueue {
            if (session.isRunning()) {
                session.stopRunning()
            }
            releaseInternal()
        }
    }

    /**
     * 取当前最近一帧 pixel buffer,并额外 CFRetain 一次交给调用方。
     * 调用方使用完必须 [CFRelease]。返回 null 表示尚未有帧到达 或 snapshot 未订阅。
     *
     * Fix #6:必须先 [beginSnapshotCapture] 让 onSample 开始 publish latestFrame,
     * 否则总是 null。SnapshotCapture 用 begin/end 包裹调用。
     */
    fun latestFramePixelBuffer(): CVImageBufferRef? = IosCameraFrameBuffer.latestFramePixelBuffer()

    /**
     * Fix #6:开始订阅 latestFrame publish。SnapshotCapture 在 takeJpeg 起手调,完成后 end。
     * 引用计数支持并发多个 SnapshotCapture 请求。
     */
    fun beginSnapshotCapture() = IosCameraFrameBuffer.beginSubscription()

    /**
     * Fix #6:结束订阅。归零时 onSample 不再 publish latestFrame。
     * 清理 latestFrame 以释放最后引用(下次 begin 再从 delegate 首帧填)。
     */
    fun endSnapshotCapture() = IosCameraFrameBuffer.endSubscription()

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
                    "IOS_CAMERA_CONTROLLER_ENCODING_START_NO_CONFIG preview 未启,using lifecycle-only fallback"
                )
                // Keep the pre-VT lifecycle contract for callers/tests that reserve an
                // encoding handle before preview is wired. No frames are encoded without
                // a config, but ref-count/generation semantics remain observable.
                _encodingActive.value = true
                return EncodingHandleImpl(generation = gen)
            }
            // 2026-07-09 sample buffer 走 sensor 原生 LandscapeRight(1280×720),VT 保持
            // 1280×720 直接对齐 buffer 尺寸,推流跟 Android 一致 landscape 1280×720。
            val session = EncodingSession(
                config = cfg,
                osdConfigFlow = osdConfigFlow,
                onFrame = ::emitEncodedFrame,
            )
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
        IosCameraFrameBuffer.recordSample()

        // Fix #6:PreviewOnly + 无 snapshot 请求时 quick exit,不做 CFRetain/CFRelease 常驻税
        val needLatest = IosCameraFrameBuffer.hasSnapshotSubscribers()
        val needEncode = _encodingActive.value
        if (!needLatest && !needEncode) return

        if (needLatest) {
            val imageBuffer = CMSampleBufferGetImageBuffer(sample) ?: return
            IosCameraFrameBuffer.publish(imageBuffer)
        }
        // T-P2-2:encoding active 时 encode 单帧,forceKey 从 pendingForceKey 消费一次
        if (needEncode) {
            val force = pendingForceKey
            if (force) pendingForceKey = false
            encodingSession?.encodeSample(sample, forceKey = force)
        }
    }

    // =========================================================
    // Internals: build AVCaptureSession
    // =========================================================

    private suspend fun <T> onSessionQueue(block: () -> T): T = suspendCoroutine { continuation ->
        dispatch_async(sessionQueue) {
            continuation.resumeWith(runCatching(block))
        }
    }

    private fun wireAndStartCaptureSession(config: CaptureConfig): Boolean {
        val device = lookupDeviceForFacing(config.cameraFacing) ?: return false

        val input: AVCaptureDeviceInput = memScoped {
            val errPtr = alloc<ObjCObjectVar<NSError?>>()
            val created = AVCaptureDeviceInput.deviceInputWithDevice(device, errPtr.ptr)
            if (created == null) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "AVCaptureDeviceInput init failed: ${errPtr.value?.localizedDescription ?: "unknown"}"
                )
                return@wireAndStartCaptureSession false
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

        val delegate = CameraSampleDelegate { sample -> onSample(sample) }
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
            // 回滚 —— 保留旧 input 语义,让上层日志能看到
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
     * - encoding 参数 / codec:若 encoding active 则重建 [EncodingSession](保留 handle refCount /
     *   generation,不影响外部消费方)
     */
    private fun reconfigureCaptureOnQueue(old: CaptureConfig?, new: CaptureConfig) {
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

        // encoding 侧:只在已 active 时重建,否则下一次 requestEncoding 会自然读 currentConfig
        val runningEncoding = encodingSession
        if (encodingParamsChanged && runningEncoding != null) {
            rebuildEncodingSessionOnQueue(new)
        }
    }

    /**
     * 用新 [config] 重建 VT 编码 session。旧 session invalidate,新 session start;失败时清空并把
     * [_encodingActive] 置 false(refCount / generation 保留,handle.close 走 stale 路径)。
     */
    private fun rebuildEncodingSessionOnQueue(config: CaptureConfig) {
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_CONTROLLER_REBUILD_ENCODING codec=${config.videoCodec.label} " +
                "${config.widthPx}x${config.heightPx}@${config.frameRate} " +
                "bitrate=${config.bitrateBps} gopSec=${config.keyframeIntervalSeconds}"
        )
        encodingSession?.invalidate()
        pendingForceKey = false
        val fresh = EncodingSession(
            config = config,
            osdConfigFlow = osdConfigFlow,
            onFrame = ::emitEncodedFrame,
        )
        if (!fresh.start()) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_REBUILD_ENCODING_FAIL VT create failed; encoding halted"
            )
            encodingSession = null
            _encodingActive.value = false
            return
        }
        encodingSession = fresh
        _encodingActive.value = true
    }

    /**
     * 把编码后的 [H264Frame] 广播到 [_frames] 并转发到录像 sink。旧写法在 requestEncoding /
     * rebuildEncodingSessionOnQueue 都要 inline 一份 lambda,提出来避免漂移。
     */
    private fun emitEncodedFrame(frame: H264Frame) {
        _frames.tryEmit(frame)
        IosRecordingFrameBridge.onVideoFrame(frame)
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

    private fun releaseInternal() {
        // T-P2-1/2 stale handle 语义 + 真 VT invalidate 已在 forceEncodingReset 内联
        forceEncodingReset()

        captureSession?.let { s ->
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

        IosCameraFrameBuffer.release()
    }

    fun lastSampleAtMs(): Long = IosCameraFrameBuffer.lastSampleAtMs()

    fun sampleCount(): Long = IosCameraFrameBuffer.sampleCount()
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

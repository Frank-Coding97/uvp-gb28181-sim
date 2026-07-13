package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import platform.AVFoundation.AVCaptureSession
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVImageBufferRef

/**
 * iOS 相机控制器 — 拆分后的 facade(cross-review R1 #4 全 4 步拆分)。
 *
 * 内部委托到 3 个协作 object:
 *  - [IosCameraSessionOwner]:AVCaptureSession + input/output/delegate + 运行期切换
 *  - [IosCameraEncodingCoordinator]:VT session refCount + generation + handle
 *  - [IosCameraFrameBuffer]:latest pixel buffer + snapshot subscription + sample stats
 *
 * facade 只做:
 *  - startPreview / stopPreview 生命周期锁(mutex)
 *  - onSample per-frame 分发(frame buffer + encoding coordinator)
 *  - OSD flow install/reset
 *  - HEVC 硬编能力探测缓存
 *
 * 外部消费点(CameraCapture / SnapshotCapture / IosAppHost / PlatformCameraPreview /
 * PlatformVideoCapabilities / CameraSessionKeepalive)只 import 本 object,不接触协作对象。
 *
 * 兼容策略:v1.2 [IosCameraStreamer.stream] 保留不动,直到 P6-1 才清理。P3/P4/P5 消费方
 * 逐个切换到 controller,期间新旧路径并存不冲突。
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
object IosCameraController {

    private val mutex = Mutex()

    // ---- Public observable state ----

    /** 当前 preview session,供 [PlatformCameraPreview] 挂 AVCaptureVideoPreviewLayer。 */
    val session: StateFlow<AVCaptureSession?> = IosCameraSessionOwner.session

    /** encoding 是否在跑;facade 委托到 [IosCameraEncodingCoordinator]。 */
    val encodingActive: StateFlow<Boolean> = IosCameraEncodingCoordinator.encodingActive

    // ---- OSD flow(preview overlay 用)----

    private val defaultOsdConfigFlow: StateFlow<OsdConfig> =
        MutableStateFlow(OsdConfig()).asStateFlow()

    @Volatile
    private var osdConfigFlow: StateFlow<OsdConfig> = defaultOsdConfigFlow

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
        val config = IosCameraSessionOwner.currentConfig()
        return (config?.widthPx ?: 1280) to (config?.heightPx ?: 720)
    }

    internal fun osdConfigFlowForTest(): StateFlow<OsdConfig> = osdConfigFlow

    internal fun resetOsdConfigFlowForTest() {
        osdConfigFlow = defaultOsdConfigFlow
    }

    // ---- HEVC 硬编能力探测 ----

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
            val result = runCatching { HevcHwProbe.probe() }.getOrElse {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "IOS_HEVC_HW_ENCODE_PROBE_EXCEPTION msg=${it.message} fallback=unsupported",
                )
                false
            }
            _hevcHwEncodeSupported = result
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_HEVC_HW_ENCODE_PROBE supported=$result",
            )
            return result
        }

    /**
     * 测试可注入的探测结果覆盖 —— 仅 iosTest 使用,让 `HevcHwEncodeProbeTest` 能覆盖
     * 硬件缺失场景。不做同步锁(测试单线程调用)。
     */
    internal fun overrideHevcHwEncodeSupportedForTest(value: Boolean?) {
        _hevcHwEncodeSupported = value
    }

    // ---- Test hooks — 委托到 SessionOwner ----

    internal fun currentFacingForTest(): CameraFacing = IosCameraSessionOwner.currentFacing()
    internal fun currentConfigForTest(): CaptureConfig? = IosCameraSessionOwner.currentConfig()
    internal fun resetPendingStateForTest() = IosCameraSessionOwner.resetPendingStateForTest()

    // ---- External session mirror (v1.2 bridge) ----

    internal fun stashConfigForEncoding(config: CaptureConfig) =
        IosCameraSessionOwner.stashConfigForEncoding(config)

    internal fun publishExternalSession(session: AVCaptureSession?) =
        IosCameraSessionOwner.publishExternalSession(session)

    // =========================================================
    // Preview API
    // =========================================================

    /**
     * 幂等启动 preview。已在运行返回 no-op。
     *
     * 内部动作:
     *   1. build AVCaptureSession + camera input(SessionOwner)
     *   2. wire CameraSampleDelegate → [onSample]
     *   3. 挂 output,startRunning
     *   4. publish session 到 [session] StateFlow
     */
    suspend fun startPreview(config: CaptureConfig) = mutex.withLock {
        if (IosCameraSessionOwner.isSessionActive()) {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_PREVIEW_ALREADY_RUNNING no-op"
            )
            return@withLock
        }
        IosCameraSessionOwner.setCurrentFacing(config.cameraFacing)
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_CONTROLLER_PREVIEW_START ${config.widthPx}x${config.heightPx}@${config.frameRate} facing=${config.cameraFacing}"
        )
        val wired = IosCameraSessionOwner.onSessionQueue {
            IosCameraSessionOwner.wireOnQueue(config, ::onSample)
        }
        if (wired) {
            IosCameraSessionOwner.setCurrentConfig(config)
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_PREVIEW_RUNNING facing=${IosCameraSessionOwner.currentFacing()}"
            )
        } else {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_PREVIEW_START_FAIL"
            )
        }
    }

    /**
     * 运行期切换前 / 后置摄像头,fire-and-forget。跟 Android [com.uvp.sim.camera.AndroidCameraStreamer.setFacing]
     * 语义对齐:同值 no-op;不同值 → 更新 currentFacing 后 dispatch 到 sessionQueue。
     *
     * 不 tear down encoding session —— VTCompressionSession 只关心像素 buffer,换朝向对它透明,
     * 已发出去的 [EncodingHandle.frames] 订阅者会无缝继续拿到新朝向的编码帧。
     */
    fun switchFacing(facing: CameraFacing) {
        if (IosCameraSessionOwner.currentFacing() == facing) return
        IosCameraSessionOwner.setCurrentFacing(facing)
        if (!IosCameraSessionOwner.isSessionActive()) {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_SWITCH_FACING_PENDING facing=$facing (preview 未启,startPreview 时应用)"
            )
            return
        }
        IosCameraSessionOwner.dispatchSwitchFacing(facing)
    }

    /**
     * 运行期改分辨率 / 帧率 / 码率 / GOP / codec / 朝向。跟 Android
     * [com.uvp.sim.camera.AndroidCameraStreamer.applyCaptureConfig] 对齐,fire-and-forget。
     *
     * 语义分层:见 [IosCameraSessionOwner.dispatchReconfigure]。
     */
    fun applyRuntimeConfig(new: CaptureConfig) {
        val old = IosCameraSessionOwner.currentConfig()
        if (old != null && old == new) return
        IosCameraSessionOwner.setCurrentConfig(new)
        IosCameraSessionOwner.setCurrentFacing(new.cameraFacing)
        if (!IosCameraSessionOwner.isSessionActive()) {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_CAMERA_CONTROLLER_APPLY_RUNTIME_CONFIG_PENDING preview 未启,配置已更新等下次 startPreview"
            )
            return
        }
        IosCameraSessionOwner.dispatchReconfigure(
            old = old,
            new = new,
            onEncodingParamsChanged = { updated ->
                IosCameraEncodingCoordinator.rebuildFor(updated, osdConfigFlow)
            },
        )
    }

    /** 停止 preview,释放 session + delegate + latest frame。幂等(未运行 no-op)。 */
    suspend fun stopPreview() = mutex.withLock {
        if (!IosCameraSessionOwner.isSessionActive()) {
            // Encoding handles may be reserved before preview is wired. They still
            // need the same generation invalidation semantics as a live session.
            IosCameraEncodingCoordinator.forceReset()
            return@withLock
        }
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_CAMERA_CONTROLLER_PREVIEW_STOP")
        IosCameraSessionOwner.onSessionQueue {
            // 顺序严格:tearDown 内部先 stopRunning,delegate queue 才不再触发 onSample。
            // 之后再 forceReset 释放 VTCompressionSession —— 否则在 stopRunning 之前
            // 已把 encodingSession invalidate,onSample 仍在 flight 会读到野指针
            // (EXC_BAD_ACCESS 0x50,cross-review R1 #4 拆分 step 3 拆反过一次)。
            IosCameraSessionOwner.tearDownOnQueue()
            IosCameraEncodingCoordinator.forceReset()
            IosCameraFrameBuffer.release()
        }
    }

    // =========================================================
    // Snapshot API — facade over IosCameraFrameBuffer
    // =========================================================

    /**
     * 取当前最近一帧 pixel buffer,并额外 CFRetain 一次交给调用方。
     * 调用方使用完必须 CFRelease。返回 null 表示尚未有帧到达 或 snapshot 未订阅。
     */
    fun latestFramePixelBuffer(): CVImageBufferRef? = IosCameraFrameBuffer.latestFramePixelBuffer()

    fun beginSnapshotCapture() = IosCameraFrameBuffer.beginSubscription()
    fun endSnapshotCapture() = IosCameraFrameBuffer.endSubscription()

    // =========================================================
    // Encoding API — facade over IosCameraEncodingCoordinator
    // =========================================================

    fun requestEncoding(): EncodingHandle =
        IosCameraEncodingCoordinator.requestEncoding(
            config = IosCameraSessionOwner.currentConfig(),
            osdFlow = osdConfigFlow,
        )

    fun requestKeyFrame() = IosCameraEncodingCoordinator.requestKeyFrame()

    // =========================================================
    // Delegate → per-frame processing
    // =========================================================

    /**
     * delegate 每帧回调。preview + no snapshot + no encode 场景走 quick exit,不做 CFRetain/CFRelease
     * 常驻税(Fix #6)。有 snapshot 订阅时 publish latest;encoding active 时委托 coordinator 编码。
     */
    private fun onSample(sample: CMSampleBufferRef) {
        IosCameraFrameBuffer.recordSample()

        val needLatest = IosCameraFrameBuffer.hasSnapshotSubscribers()
        val needEncode = IosCameraEncodingCoordinator.isActive()
        if (!needLatest && !needEncode) return

        if (needLatest) {
            val imageBuffer = CMSampleBufferGetImageBuffer(sample) ?: return
            IosCameraFrameBuffer.publish(imageBuffer)
        }
        if (needEncode) {
            IosCameraEncodingCoordinator.encodeSample(sample)
        }
    }

    fun lastSampleAtMs(): Long = IosCameraFrameBuffer.lastSampleAtMs()
    fun sampleCount(): Long = IosCameraFrameBuffer.sampleCount()
}

/**
 * v1.3-A encoding path 引用计数句柄。
 *
 * 语义:controller.[IosCameraController.requestEncoding] 每次返回一个 handle,refCount++。
 * [close] refCount--,归零时 controller invalidate VTCompressionSession。
 *
 * stale handle 语义:controller.stopPreview 强制 refCount 归 0 时,已发出但未 close 的
 * handle 变 no-op(内部持 generation counter,close 时校验)。
 */
interface EncodingHandle {
    /** encoding 帧流。encoding 结束(所有 handle close)后 completes。 */
    val frames: kotlinx.coroutines.flow.Flow<com.uvp.sim.media.H264Frame>

    /** 幂等 close。stale handle no-op。 */
    fun close()
}

/**
 * Fix #1/#4 no-op handle for encoding failure paths(no config / VT create fail).
 * frames 立即完成的 emptyFlow → 消费方 collect 拿到 no data 且 flow completes,
 * 不会挂死。close 完全 no-op,不进 refCount 下溢。
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

package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.media.H264Frame
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.IosRecordingFrameBridge
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import platform.CoreMedia.CMSampleBufferRef

/**
 * cross-review R1 #4 拆分 step 2(from [IosCameraController]):
 * 编码 session 引用计数 + generation stale-handle 协调器。
 *
 * 语义完全跟拆分前一致:
 *  - 首次 [requestEncoding]:VTCompressionSession create + [encodingActive] = true
 *  - 末次 handle.close:invalidate + [encodingActive] = false
 *  - [forceReset](由 controller.releaseInternal 调):递增 generation 让所有旧 handle 变 stale
 *  - [rebuildFor](由 controller.reconfigureCaptureOnQueue 调):重建 VT session 应用新参数
 *
 * 单例 object,跟 [IosCameraController] 一样进程级。多路消费方(INVITE 拉流 / 录像)
 * 通过 refCount 共享同一份 VT session,避免重复 create 编 2 路 H.264。
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal object IosCameraEncodingCoordinator {

    private val _encodingActive = MutableStateFlow(false)
    /** encoding 是否在跑;暴露给 [IosCameraController.encodingActive] facade。 */
    val encodingActive: StateFlow<Boolean> = _encodingActive.asStateFlow()

    /**
     * T-P2-2:frames 广播 SharedFlow。EncodingSession 产 H264Frame 后回调本 coordinator,
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
    /** 广播 flow — 所有 [EncodingHandle.frames] 订阅者共享同一份。 */
    val frames: SharedFlow<H264Frame> = _frames.asSharedFlow()

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
     * T-P2-2:VTCompressionSession 生命周期封装。首次 requestEncoding 时 create,末次 close
     * (或 forceReset)时 invalidate。SPS/PPS + AVCC→Annex-B split 都在里面。
     */
    @Volatile
    private var encodingSession: EncodingSession? = null

    /**
     * T-P2-2:force-key 请求 pending 标志。requestKeyFrame 置 true,encodeSample 下一帧
     * consume 并清零。VideoToolbox force-key 属性只在 encodeSample 传入 frameProperties 才生效。
     */
    @Volatile
    private var pendingForceKey: Boolean = false

    /**
     * P2-1:引用计数首次触发真 encoding start,末次 close encoding stop。
     * 每次调用返回一个新 handle,不同 handle 共享同一份 VT session lifecycle。
     *
     * @param config 首帧 encoding 需要的 CaptureConfig;null 走 lifecycle-only fallback
     *   (跟 pre-VT 行为契约保持一致,供 test / handle-before-preview reserve)。
     * @param osdFlow OSD 数据源,EncodingSession 每帧 pull 拼图。
     */
    fun requestEncoding(config: CaptureConfig?, osdFlow: StateFlow<OsdConfig>): EncodingHandle {
        val gen = encodingGeneration
        encodingRefCount += 1
        val newCount = encodingRefCount
        if (newCount == 1) {
            if (config == null) {
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
                config = config,
                osdConfigFlow = osdFlow,
                onFrame = ::emitEncodedFrame,
            )
            if (!session.start()) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_CAMERA_CONTROLLER_ENCODING_START_FAIL VT create failed"
                )
                encodingRefCount = 0  // Fix #1:rollback + NoOp handle
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
     * P2-2:置 pendingForceKey 让下一个 encode 走 force-key 路径。
     * encoding 未启时 no-op。
     */
    fun requestKeyFrame() {
        if (!_encodingActive.value) return
        pendingForceKey = true
        SystemLogger.emit(
            LogLevel.Debug, LogTag.Media,
            "IOS_CAMERA_CONTROLLER_REQUEST_KEYFRAME pendingForceKey=true"
        )
    }

    /** encoding 是否在跑,给 controller.onSample 决定是否要 encodeSample。 */
    fun isActive(): Boolean = _encodingActive.value

    /**
     * controller.onSample 里 encoding 分支:委托给 VT session,消费 pendingForceKey。
     */
    fun encodeSample(sample: CMSampleBufferRef) {
        val force = pendingForceKey
        if (force) pendingForceKey = false
        encodingSession?.encodeSample(sample, forceKey = force)
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
     * 由 [IosCameraController.releaseInternal] 调用。
     */
    fun forceReset() {
        if (encodingRefCount > 0 || _encodingActive.value || encodingSession != null) {
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
     * 用新 [config] 重建 VT 编码 session。旧 session invalidate,新 session start;失败时清空并把
     * [encodingActive] 置 false(refCount / generation 保留,handle.close 走 stale 路径)。
     *
     * 由 controller.reconfigureCaptureOnQueue 在 encoding params 变化时调。
     * 已 encoding 才重建 —— 未 active 则下次 requestEncoding 会用最新 config,不需要提前建。
     */
    fun rebuildFor(config: CaptureConfig, osdFlow: StateFlow<OsdConfig>) {
        val running = encodingSession ?: return
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_CAMERA_CONTROLLER_REBUILD_ENCODING codec=${config.videoCodec.label} " +
                "${config.widthPx}x${config.heightPx}@${config.frameRate} " +
                "bitrate=${config.bitrateBps} gopSec=${config.keyframeIntervalSeconds}"
        )
        running.invalidate()
        pendingForceKey = false
        val fresh = EncodingSession(
            config = config,
            osdConfigFlow = osdFlow,
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
     * rebuildFor 都要 inline 一份 lambda,提出来避免漂移。
     */
    private fun emitEncodedFrame(frame: H264Frame) {
        _frames.tryEmit(frame)
        IosRecordingFrameBridge.onVideoFrame(frame)
    }

    /**
     * [EncodingHandle] 具体实现。持 generation 快照,close 时通过 coordinator 校验。
     */
    private class EncodingHandleImpl(private val generation: Int) : EncodingHandle {
        // frames = coordinator 的 SharedFlow<H264Frame> 广播,所有 handle 共享同一份。
        // encoding 结束(refCount 归 0 或 forceReset)后 SharedFlow 不再 emit;
        // 订阅者调用点通过 handle.close 触发 coordinator 释放语义,自身 collect 应配合 handle
        // 生命周期(见 CameraCapture.ios / IosRecordingService 消费点)。
        override val frames: kotlinx.coroutines.flow.Flow<H264Frame> = IosCameraEncodingCoordinator.frames

        @Volatile
        private var closed: Boolean = false

        override fun close() {
            if (closed) return
            closed = true
            closeHandleInternal(generation)
        }
    }
}

package com.uvp.sim.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.H265NalType
import com.uvp.sim.media.NalType
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.osd.OsdRenderer
import com.uvp.sim.osd.OsdRendererHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android camera + H.264 streamer with two independent CameraX Preview use cases:
 *
 *   1. **Screen preview** — bound via [attachPreviewView]. Lets the user see what
 *      the camera sees, regardless of whether SIP is streaming. The user calls
 *      this from a Compose `AndroidView { PreviewView(...) }` once registered.
 *   2. **Encoder feed** — bound when [stream] starts collecting. Routes camera
 *      frames into a MediaCodec InputSurface for H.264 encoding.
 *
 * Lifecycle: streamer owns its own [LifecycleRegistry] held permanently in
 * STARTED. CameraX auto-unbinds when the bound owner drops below STARTED, which
 * means an Activity or ProcessLifecycleOwner can't be used here — Activity
 * onStop / locked screen would silently kill streaming and recording. The
 * caller's Activity drives only [attachPreviewView] / [detachPreviewView]; the
 * camera pipeline keeps running across configuration changes and backgrounding.
 *
 * Provider sharing: [ProcessCameraProvider] is a process singleton — recording
 * (AndroidRecordingService) shares the same instance. We never call
 * [ProcessCameraProvider.unbindAll]; instead each subsystem unbinds only its
 * own use cases via [ProcessCameraProvider.unbind] so the others survive.
 *
 * Threading: CameraX bindToLifecycle must run on the main thread. We marshal
 * via [mainExecutor]. Encoder callbacks run on whatever MediaCodec picks; we
 * forward H264Frames into the Flow and let collectors switch dispatchers.
 */
class AndroidCameraStreamer(
    private val context: Context,
    private val mainExecutor: Executor,
    private val config: CaptureConfig,
    /**
     * OSD 视频叠加层配置 — null 关闭 OSD(走原 CameraX 直连 MediaCodec 路径)。
     *
     * 当前 scope:OSD 仅作用于直播推流(stream() 路径),录像(VideoCapture)和回放
     * (PlaybackEngine)不烧戳。屏幕预览也保留独立 CameraX Preview(无 OSD),仅 WVP
     * 端能看到 OSD。详见 specs/osd-overlay.md scope 调整说明。
     */
    private val osdConfigFlow: kotlinx.coroutines.flow.StateFlow<OsdConfig>? = null
) {
    /**
     * Self-driven lifecycle pinned to STARTED. Avoids being torn down by Activity
     * onStop / process backgrounding. Released only on [release].
     */
    private val lifecycleOwner: LifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        init { mainExecutor.execute { registry.currentState = Lifecycle.State.STARTED } }
    }
    private val lifecycleRegistry: LifecycleRegistry
        get() = lifecycleOwner.lifecycle as LifecycleRegistry

    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null

    /**
     * OSD 渲染器引用 — streamer 持有的 holder 引用,在第一次 stream 或 attachPreviewView
     * 时通过 OsdRendererHolder.acquire 获取。生命周期跟 streamer 绑定,release() 时归还。
     *
     * 摄像头 Preview UseCase 持续输出到 [persistentOsdRenderer.cameraInputSurface],
     * 跟 stream/record 解耦 — 任何消费者(直播/录像/屏幕)都从这一份画面源拿。
     */
    private var persistentOsdRenderer: OsdRenderer? = null
    private var persistentOsdHeld = false

    @Volatile private var screenPreview: Preview? = null  // 老 PreviewView 路径已弃用,字段保留过渡期
    @Volatile private var encoderPreview: Preview? = null  // OSD 关时直连 encoder surface 的 fallback Preview UseCase
    @Volatile private var cameraToOsdPreview: Preview? = null  // 持续输出到 OsdRenderer.cameraInputSurface 的 Preview UseCase

    @Volatile private var provider: ProcessCameraProvider? = null

    /**
     * 当前摄像头朝向。初值取构造 [config].cameraFacing,运行期可由 [setFacing] 改。
     * 双真实通道:平台对前置/后置通道发 INVITE 时,引擎据通道映射切朝向。
     */
    @Volatile private var currentFacing: CameraFacing = config.cameraFacing

    /**
     * 绑定屏幕预览 SurfaceView(P0-PREVIEW,2026-06-14)。
     *
     * 跟工业 IPC 同构:屏幕看到的画面来自 OsdRendererHolder 单一画面源,跟直播/录像同源。
     * 流程:
     * 1. 第一次 attach → ensurePersistentOsd() 启 OsdRendererHolder + bind 摄像头到 cameraInputSurface
     * 2. 监听 SurfaceHolder.Callback,surface 创建/尺寸变 → renderer.setScreenSurface
     * 3. surface destroyed → renderer.setScreenSurface(null)
     */
    fun attachPreviewView(view: SurfaceView) {
        runOnMain {
            ensurePersistentOsd()
            val renderer = persistentOsdRenderer ?: run {
                SystemLogger.emit(LogLevel.Warning, LogTag.Media, "屏幕预览失败:OsdRenderer 未启动")
                return@runOnMain
            }
            // 注册 SurfaceHolder.Callback,surface 生命周期反映到 renderer
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    renderer.setScreenSurface(holder.surface, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    renderer.setScreenSurface(holder.surface, width, height)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    renderer.setScreenSurface(null, 0, 0)
                }
            })
            // 如果 surface 已经存在(view 已 attached 到 window),直接通知一次
            val existing = view.holder.surface
            if (existing != null && existing.isValid) {
                renderer.setScreenSurface(existing, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
            }
        }
    }

    /** 解绑屏幕预览。OsdRendererHolder 引用通过 release() 路径归还。 */
    fun detachPreviewView() {
        runOnMain {
            persistentOsdRenderer?.setScreenSurface(null, 0, 0)
        }
    }

    /**
     * 确保 OsdRendererHolder 启动并 bind 摄像头 Preview UseCase 到 cameraInputSurface。
     *
     * 这是工业 IPC "sensor → ISP region 常驻" 的软件等价。多次调用幂等:已启动则跳过。
     *
     * 失败 fallback:OSD 启动不了时持久 renderer = null,后续 attachPreviewView 会
     * emit OSD 屏幕预览失败,但 stream() / record 仍能走老路径(虽然现在 stream 也依赖 OSD,
     * 失败的话 stream 也得 fallback)。
     */
    private fun ensurePersistentOsd() {
        if (persistentOsdRenderer != null) return
        val osdFlow = osdConfigFlow ?: return  // OSD 关掉,屏幕预览也不走这条路
        val renderer = OsdRendererHolder.acquire(
            context = context,
            configFlow = osdFlow,
            targetWidth = config.widthPx,
            targetHeight = config.heightPx
        ) ?: return
        persistentOsdRenderer = renderer
        persistentOsdHeld = true
        // 让摄像头 Preview UseCase 持续输出到 OsdRenderer.cameraInputSurface
        runOnMain {
            try {
                if (provider == null) provider = awaitCameraProviderBlocking(context)
                bindCameraToOsdInput(renderer)
                rebind()
            } catch (t: Throwable) {
                SystemLogger.emit(LogLevel.Warning, LogTag.Media, "OSD 持久 Preview UseCase bind 失败: ${t.message}")
            }
        }
    }

    private fun bindCameraToOsdInput(renderer: OsdRenderer) {
        val target = renderer.cameraInputSurface ?: return
        val targetSize = Size(config.widthPx, config.heightPx)
        val preview = Preview.Builder()
            .setTargetResolution(targetSize)
            .build()
            .also { p ->
                p.setSurfaceProvider { request ->
                    val bufferSize = request.resolution
                    val frameSize = displayFrameSizeFor(bufferSize)
                    renderer.configureCameraInput(
                        bufferWidth = bufferSize.width,
                        bufferHeight = bufferSize.height,
                        frameWidth = frameSize.width,
                        frameHeight = frameSize.height
                    )
                    request.provideSurface(target, mainExecutor) { /* released on streamer.release */ }
                }
            }
        cameraToOsdPreview = preview
    }

    /** Hot Flow of encoded H.264 frames. Cancelling the collection stops encoding. */
    fun stream(): Flow<H264Frame> = callbackFlow {
        val mime = when (config.videoCodec) {
            com.uvp.sim.media.VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            com.uvp.sim.media.VideoCodec.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        }
        val format = MediaFormat.createVideoFormat(
            mime,
            config.widthPx,
            config.heightPx
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSeconds)
        }
        val codec = try {
            MediaCodec.createEncoderByType(mime).apply {
                configure(format, /*surface=*/null, /*crypto=*/null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Throwable) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "${config.videoCodec} 编码器初始化失败: ${e::class.simpleName}: ${e.message}"
            )
            close(e)
            throw e
        }
        val surface = codec.createInputSurface()
        encoder = codec
        encoderInputSurface = surface

        // OSD: 通过 OsdRendererHolder 注册 encoder surface,持久 Preview UseCase 已经在
        // ensurePersistentOsd() 时 bind 给 cameraInputSurface,这里不再独立创 Preview。
        // 失败 fallback:回退到老路径(独立 Preview UseCase 直连 encoder surface,无 OSD)。
        val osdFlow = osdConfigFlow
        val osdEnabled: Boolean = if (osdFlow != null) {
            ensurePersistentOsd()  // 幂等,已启动则跳过
            val renderer = persistentOsdRenderer
            if (renderer != null) {
                renderer.addEncoderSurface(OSD_ENCODER_TAG_LIVE, surface, config.widthPx, config.heightPx)
                true
            } else false
        } else false

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "打开摄像头 ${config.widthPx}x${config.heightPx}@${config.frameRate}fps · ${config.videoCodec} ${config.bitrateBps / 1000}kbps · OSD ${if (osdEnabled) "ON" else "OFF"}"
        )

        // Parameter sets to prepend to every key frame. For H.264 these are
        // SPS+PPS; for H.265 they are VPS+SPS+PPS. We keep the latest copy each
        // time the encoder emits a CODEC_CONFIG buffer.
        val paramSets = mutableMapOf<Int, ByteArray>()

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { /* surface input */ }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer == null) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                val raw = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(raw, 0, info.size)
                codec.releaseOutputBuffer(index, false)

                val nals = AnnexB.splitNals(raw)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    nals.forEach { nal ->
                        val type = config.videoCodec.nalType(nal[0])
                        if (config.videoCodec.isParameterSet(type)) {
                            paramSets[type] = nal
                        }
                    }
                    return
                }
                val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val finalNals = nals.toMutableList()
                if (isKeyFrame) {
                    // Prepend any param sets that the bitstream itself didn't include.
                    val existing = finalNals.map { config.videoCodec.nalType(it[0]) }.toSet()
                    val ordered = when (config.videoCodec) {
                        com.uvp.sim.media.VideoCodec.H264 ->
                            listOf(NalType.SPS, NalType.PPS)
                        com.uvp.sim.media.VideoCodec.H265 ->
                            listOf(H265NalType.VPS_NUT, H265NalType.SPS_NUT, H265NalType.PPS_NUT)
                    }
                    var insertAt = 0
                    for (psType in ordered) {
                        if (psType in existing) continue
                        val ps = paramSets[psType] ?: continue
                        finalNals.add(insertAt, ps)
                        insertAt++
                    }
                }
                trySend(
                    H264Frame(
                        nalUnits = finalNals,
                        timestampUs = info.presentationTimeUs,
                        isKeyFrame = isKeyFrame,
                        codec = config.videoCodec
                    )
                )
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "编码器异常: ${e.message}"
                )
                close(e)
            }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) { /* CSD via CODEC_CONFIG */ }
        })
        codec.start()

        runOnMain {
            try {
                if (provider == null) {
                    provider = awaitCameraProviderBlocking(context)
                }
                // OSD on: cameraToOsdPreview 已在 ensurePersistentOsd 时 bind,这里只 rebind 触发统一刷新
                // OSD off (fallback): 走老路径 bindEncoderPreview 把 encoder surface 直连 Preview UseCase
                if (!osdEnabled) {
                    bindEncoderPreview(surface)
                }
                rebind()
            } catch (e: Throwable) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "绑定 CameraX 失败: ${e::class.simpleName}: ${e.message}"
                )
                close(e)
            }
        }

        awaitClose {
            runOnMain {
                if (!osdEnabled) {
                    encoderPreview = null
                    rebind()
                }
            }
            // OSD on:解注册 encoder surface,持久 cameraToOsdPreview 不动(屏幕预览还要看)
            if (osdEnabled) {
                persistentOsdRenderer?.removeEncoderSurface(OSD_ENCODER_TAG_LIVE)
            }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            encoder = null
            encoderInputSurface = null
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "关闭摄像头编码器")
        }
    }.flowOn(Dispatchers.IO)

    suspend fun stop() {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { encoderInputSurface?.release() }
        encoder = null
        encoderInputSurface = null
    }

    /**
     * Force the encoder to emit a key frame on the next pass.
     * Used in response to GB28181 IFameCmd from the platform (§9.3.4).
     */
    fun requestKeyFrame() {
        val codec = encoder ?: return
        val params = android.os.Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        runCatching { codec.setParameters(params) }
    }

    // ============= internal binding =============

    private fun bindEncoderPreview(surface: Surface) {
        val targetSize = Size(config.widthPx, config.heightPx)
        val preview = Preview.Builder()
            .setTargetResolution(targetSize)
            .build()
            .also { p ->
                p.setSurfaceProvider { request ->
                    request.provideSurface(surface, mainExecutor) { /* released on awaitClose */ }
                }
            }
        encoderPreview = preview
    }

    private fun displayFrameSizeFor(bufferSize: Size): Size {
        val targetAspect = config.widthPx.toFloat() / config.heightPx
        val normalDelta = aspectDelta(bufferSize.width, bufferSize.height, targetAspect)
        val swappedDelta = aspectDelta(bufferSize.height, bufferSize.width, targetAspect)
        return if (swappedDelta + ASPECT_EPSILON < normalDelta) {
            Size(bufferSize.height, bufferSize.width)
        } else {
            bufferSize
        }
    }

    private fun aspectDelta(width: Int, height: Int, targetAspect: Float): Float =
        abs(width.toFloat() / height - targetAspect)

    /** Rebind whichever use cases are currently set. Must run on main thread.
     *
     *  Critical: we MUST NOT call [ProcessCameraProvider.unbindAll] — that wipes
     *  use cases owned by other subsystems (e.g. AndroidRecordingService's
     *  VideoCapture), causing silent recording stops and preview blackouts.
     *  Instead unbind only the previous *streamer* cases. Any UseCase already
     *  bound by another owner is left intact. */
    private val boundCases = mutableListOf<UseCase>()
    private fun rebind() {
        val prov = provider ?: return
        val selector = when (currentFacing) {
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        val nextCases = mutableListOf<UseCase>()
        cameraToOsdPreview?.let { nextCases += it }
        encoderPreview?.let { nextCases += it }
        try {
            if (boundCases.isNotEmpty()) {
                prov.unbind(*boundCases.toTypedArray())
                boundCases.clear()
            }
            if (nextCases.isNotEmpty()) {
                prov.bindToLifecycle(lifecycleOwner, selector, *nextCases.toTypedArray())
                boundCases += nextCases
            }
        } catch (_: Throwable) { /* swallow — unbind path */ }
    }

    /**
     * 运行期切换摄像头朝向(双真实通道)。值变才 rebind,避免无谓重绑。
     * 由 CameraCapture.setFacing 转发。必须在引擎"无活跃直播流"时调用
     * (B 方案保证并发只一路,故切换不打断正在推的流)。
     */
    fun setFacing(facing: CameraFacing) {
        if (currentFacing == facing) return
        currentFacing = facing
        runOnMain { rebind() }
    }

    /** Release the self-driven lifecycle so CameraX drops our use cases.
     *  Caller (Activity onDestroy of the *last* instance / process exit). */
    fun release() {
        runOnMain {
            try {
                if (boundCases.isNotEmpty()) {
                    provider?.unbind(*boundCases.toTypedArray())
                    boundCases.clear()
                }
            } catch (_: Throwable) { }
            // 归还 OsdRendererHolder 引用,可能触发 GL pipeline tear down
            if (persistentOsdHeld) {
                persistentOsdRenderer?.setScreenSurface(null, 0, 0)
                OsdRendererHolder.release()
                persistentOsdRenderer = null
                persistentOsdHeld = false
            }
            cameraToOsdPreview = null
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    /** Exposed for AndroidRecordingService — both share the same provider singleton.
     *  Returns null if streamer hasn't acquired the provider yet (rare; first call
     *  to attachPreviewView/stream() forces acquisition). */
    fun cameraProvider(): ProcessCameraProvider? = provider

    /** Block until the provider has been acquired, then return it together with
     *  the self-driven STARTED lifecycle. Used by AndroidRecordingService.start.
     *  Safe to call from any dispatcher; it blocks the current thread briefly via
     *  ListenableFuture.get on first call only. */
    fun awaitCameraOwner(): Pair<ProcessCameraProvider, LifecycleOwner> {
        val p = provider ?: awaitCameraProviderBlocking(context).also { provider = it }
        return p to lifecycleOwner
    }

    /** The self-driven STARTED lifecycle. AndroidRecordingService binds its
     *  VideoCapture to this owner so recording survives Activity backgrounding. */
    fun streamerLifecycleOwner(): LifecycleOwner = lifecycleOwner

    private fun runOnMain(block: () -> Unit) {
        mainExecutor.execute(block)
    }

    companion object {
        const val OSD_ENCODER_TAG_LIVE = "live"
        private const val ASPECT_EPSILON = 0.01f

        private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
            suspendCancellableCoroutine { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    {
                        try { cont.resume(future.get()) }
                        catch (e: Throwable) { cont.resumeWithException(e) }
                    },
                    Runnable::run
                )
            }

        private fun awaitCameraProviderBlocking(context: Context): ProcessCameraProvider =
            ProcessCameraProvider.getInstance(context).get()
    }
}

package com.uvp.sim.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.H265NalType
import com.uvp.sim.media.NalType
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
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
 * Both use cases share one [ProcessCameraProvider]. We re-bind whenever either
 * side changes (provider only allows one bindToLifecycle per lifecycle owner —
 * subsequent calls overwrite, so we always re-bind the *current set*).
 *
 * Threading: CameraX bindToLifecycle must run on the main thread. We marshal
 * via [mainExecutor]. Encoder callbacks run on whatever MediaCodec picks; we
 * forward H264Frames into the Flow and let collectors switch dispatchers.
 */
class AndroidCameraStreamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val mainExecutor: Executor,
    private val config: CaptureConfig
) {
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null

    @Volatile private var screenPreview: Preview? = null
    @Volatile private var encoderPreview: Preview? = null

    @Volatile private var provider: ProcessCameraProvider? = null

    /** Bind a screen-side PreviewView so the user can see live camera frames. */
    fun attachPreviewView(view: PreviewView) {
        runOnMain {
            val prov = provider ?: run {
                // Lazily acquire provider on a background path; rebind once ready.
                CoroutineScope(Dispatchers.Main).launch {
                    val p = withContext(Dispatchers.IO) { awaitCameraProvider(context) }
                    provider = p
                    bindScreenPreview(view)
                    rebind()
                }
                return@runOnMain
            }
            bindScreenPreview(view)
            rebind()
        }
    }

    /** Detach screen preview (e.g. activity stopped); encoder feed is unaffected. */
    fun detachPreviewView() {
        runOnMain {
            screenPreview = null
            rebind()
        }
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
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "打开摄像头 ${config.widthPx}x${config.heightPx}@${config.frameRate}fps · ${config.videoCodec} ${config.bitrateBps / 1000}kbps"
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
                bindEncoderPreview(surface)
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
                encoderPreview = null
                rebind()
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

    // ============= internal binding =============

    private fun bindScreenPreview(view: PreviewView) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(view.surfaceProvider)
        }
        screenPreview = preview
    }

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

    /** Rebind whichever use cases are currently set. Must run on main thread. */
    private fun rebind() {
        val prov = provider ?: return
        val selector = when (config.cameraFacing) {
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        val cases = mutableListOf<UseCase>()
        screenPreview?.let { cases += it }
        encoderPreview?.let { cases += it }
        try {
            prov.unbindAll()
            if (cases.isNotEmpty()) {
                prov.bindToLifecycle(lifecycleOwner, selector, *cases.toTypedArray())
            }
        } catch (_: Throwable) { /* swallow — unbind path */ }
    }

    private fun runOnMain(block: () -> Unit) {
        mainExecutor.execute(block)
    }

    companion object {
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

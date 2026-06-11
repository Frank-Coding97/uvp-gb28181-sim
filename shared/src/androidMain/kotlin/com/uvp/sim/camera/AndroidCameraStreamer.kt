package com.uvp.sim.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.NalType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android camera + H.264 streamer that wires CameraX → MediaCodec input surface.
 *
 * Design:
 *   1. MediaCodec is configured with COLOR_FormatSurface and createInputSurface()
 *      gives us a Surface that the encoder reads from.
 *   2. CameraX Preview use case has its SurfaceProvider hand back our encoder
 *      surface; CameraX writes camera frames straight in.
 *   3. The encoder's output buffers are scraped on a callback, NAL units
 *      extracted (Annex-B), and emitted as [H264Frame] over a Flow.
 *
 * Lifecycle is bound to the provided [LifecycleOwner]; collecting [stream]
 * starts capture, cancelling the collection stops it. [stop] is also exposed
 * for explicit teardown.
 */
class AndroidCameraStreamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val mainExecutor: Executor,
    private val config: CaptureConfig
) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    /** Returns a hot Flow that captures camera frames as long as it's collected. */
    fun stream(): Flow<H264Frame> = callbackFlow {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
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
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, /*surface=*/null, /*crypto=*/null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = codec.createInputSurface()
        encoder = codec
        inputSurface = surface

        var sps: ByteArray? = null
        var pps: ByteArray? = null

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { /* unused for surface input */ }

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
                    sps = nals.firstOrNull { (it[0].toInt() and 0x1F) == NalType.SPS }
                    pps = nals.firstOrNull { (it[0].toInt() and 0x1F) == NalType.PPS }
                    return
                }

                val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val finalNals = nals.toMutableList()
                if (isKeyFrame) {
                    val sp = sps; val pp = pps
                    if (sp != null && finalNals.firstOrNull { (it[0].toInt() and 0x1F) == NalType.SPS } == null) {
                        finalNals.add(0, sp)
                    }
                    if (pp != null && finalNals.firstOrNull { (it[0].toInt() and 0x1F) == NalType.PPS } == null) {
                        finalNals.add(if (sp != null) 1 else 0, pp)
                    }
                }
                trySend(
                    H264Frame(
                        nalUnits = finalNals,
                        timestampUs = info.presentationTimeUs,
                        isKeyFrame = isKeyFrame
                    )
                )
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                close(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // CSD arrives via CODEC_CONFIG buffers
            }
        })
        codec.start()

        // Bind CameraX Preview to feed our encoder's input Surface.
        val provider = awaitCameraProvider(context)
        val targetSize = Size(config.widthPx, config.heightPx)
        val preview = Preview.Builder()
            .setTargetResolution(targetSize)
            .build()
            .also { p ->
                p.setSurfaceProvider { request ->
                    request.provideSurface(surface, mainExecutor) { /* released on awaitClose */ }
                }
            }
        val selector = when (config.cameraFacing) {
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        try {
            // CameraX bindToLifecycle must run on main thread. We assume the
            // caller already invokes us on Dispatchers.Main, or via SipViewModel.
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview)
        } catch (e: Throwable) {
            close(e)
        }

        awaitClose {
            runCatching { provider.unbindAll() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            encoder = null
            inputSurface = null
        }
    }

    suspend fun stop() {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { inputSurface?.release() }
        encoder = null
        inputSurface = null
    }

    companion object {
        private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
            suspendCancellableCoroutine { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    {
                        try {
                            cont.resume(future.get())
                        } catch (e: Throwable) {
                            cont.resumeWithException(e)
                        }
                    },
                    Runnable::run
                )
            }
    }
}

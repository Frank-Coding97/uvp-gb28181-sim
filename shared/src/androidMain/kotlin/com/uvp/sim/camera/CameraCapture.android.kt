package com.uvp.sim.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.NalType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android implementation of [CameraCapture].
 *
 * **M1 scope (this commit)**: encoder + NAL extraction logic. Camera bind to
 * MediaCodec input Surface needs CameraX which requires an Android Context;
 * the integration is wired in T08 once we have a hosting Activity.
 *
 * What this file provides today:
 *   - MediaCodec H.264 hardware encoder configuration
 *   - Output buffer extraction → strip start codes → split NAL units (via [AnnexB])
 *   - SPS/PPS extracted from CSD-0 (codec config on first key frame)
 *   - Flow emission with timestamp + isKeyFrame flag
 *
 * What's deferred to T08:
 *   - CameraX preview ↔ MediaCodec input surface binding
 *   - Permission request flow (CAMERA + RECORD_AUDIO)
 *   - Lifecycle integration (Compose / Activity)
 */
actual class CameraCapture actual constructor(private val config: CaptureConfig) {

    private var encoder: MediaCodec? = null

    actual fun start(): Flow<H264Frame> = callbackFlow {
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
        encoder = codec

        var sps: ByteArray? = null
        var pps: ByteArray? = null

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Filled by camera surface in T08
            }

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
                // CSD comes via onOutputBufferAvailable + CODEC_CONFIG flag
            }
        })

        codec.start()

        awaitClose {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            encoder = null
        }
    }

    actual suspend fun stop() {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
    }
}

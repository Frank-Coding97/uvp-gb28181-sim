package com.uvp.sim.camera

import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.G711
import com.uvp.sim.media.MediaTimebase
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.AVFAudio.AVAudioCommonFormat
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioTime
import platform.Foundation.NSError

/**
 * iOS audio capture + encode.
 *
 * T8-follow-up: AVAudioEngine + installTapOnBus wiring. The tap block is an ObjC
 * block (not a C function pointer), which Kotlin/Native's cinterop wraps
 * automatically from a Kotlin lambda when the signature matches. Contrast with
 * [IosCameraStreamer], which needs `staticCFunction` because VTCompression's
 * output callback is a C function pointer.
 *
 * Design:
 *
 *   1. AVAudioEngine.inputNode → 16-bit interleaved PCM at 8 kHz mono
 *   2. installTapOnBus(0, bufferSize = 160 samples / 20ms)
 *   3. For each buffer: samples → G711.encodeAlaw/encodeUlaw → AudioFrame emit
 *   4. Timestamp: MediaTimebase.nowUs() at emit time
 *
 * The 20 ms frame convention matches Android AndroidAudioStreamer.streamG711
 * for cross-platform RTP packer parity. Note the hardware may deliver a
 * buffer with frameLength != 160 (early call, sample rate mismatch); we
 * defensively read frameLength and encode exactly that many samples.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosAudioStreamer(private val config: AudioCaptureConfig) {

    private var engine: AVAudioEngine? = null

    /**
     * Emit compressed audio frames.
     */
    fun stream(): Flow<AudioFrame> = when (config.codec) {
        AudioCodec.G711A, AudioCodec.G711U -> streamG711()
        AudioCodec.AAC -> streamAac()
    }

    private fun streamG711(): Flow<AudioFrame> = callbackFlow {
        val eng = AVAudioEngine()
        val input = eng.inputNode
        val format = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = SAMPLE_RATE_HZ,
            channels = CHANNELS,
            interleaved = true,
        )

        input.installTapOnBus(
            bus = 0u,
            bufferSize = BUFFER_FRAMES,
            format = format,
        ) { buffer: AVAudioPCMBuffer?, _: AVAudioTime? ->
            if (buffer == null) return@installTapOnBus
            val frames = buffer.frameLength.toInt()
            if (frames <= 0) return@installTapOnBus
            val channelPtr: CPointer<ShortVar> =
                buffer.int16ChannelData?.pointed?.value ?: return@installTapOnBus

            val pcm = ShortArray(frames)
            for (i in 0 until frames) {
                pcm[i] = channelPtr[i]
            }
            val frame = encodePcmToG711Frame(pcm, config.codec, MediaTimebase.nowUs())
            trySend(frame)
        }

        eng.prepare()
        val started = memScoped {
            val errPtr = alloc<ObjCObjectVar<NSError?>>()
            val ok = eng.startAndReturnError(errPtr.ptr)
            if (!ok) {
                val desc = errPtr.value?.localizedDescription ?: "unknown"
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_AUDIO_START_FAILED codec=${config.codec} error=$desc",
                )
            }
            ok
        }
        if (!started) {
            input.removeTapOnBus(0u)
            close(IllegalStateException("AVAudioEngine.start failed"))
            return@callbackFlow
        }

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_AUDIO_START codec=${config.codec} sr=${SAMPLE_RATE_HZ.toInt()} ch=${CHANNELS.toInt()}",
        )
        engine = eng

        awaitClose {
            input.removeTapOnBus(0u)
            eng.stop()
            engine = null
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_AUDIO_STOP codec=${config.codec}")
        }
    }

    private fun streamAac(): Flow<AudioFrame> = callbackFlow {
        // T10 explicit skip (spec §4.2): G.711A covers 99% of GB28181 platforms.
        // If a platform truly needs AAC, throw so the caller sees the gap
        // rather than silently getting no audio.
        close(UnsupportedOperationException("AAC audio on iOS is a v1.1 follow-up (T10 skipped); use G711A"))
        awaitClose { }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun stop() {
        engine?.let {
            it.inputNode.removeTapOnBus(0u)
            it.stop()
        }
        engine = null
    }

    /** Test hook — verify the streamer accepts the configured codec without crashing. */
    fun configuredCodec(): AudioCodec = config.codec

    private companion object {
        const val SAMPLE_RATE_HZ: Double = 8000.0
        const val CHANNELS: UInt = 1u
        // 20 ms @ 8 kHz = 160 samples; matches Android streamG711 and the RTP packer.
        const val BUFFER_FRAMES: UInt = 160u
    }
}

/**
 * Encode one 20ms PCM buffer to a G.711 [AudioFrame]. Public utility so the
 * AVAudioEngine tap callback (and iosTest fixtures) can share the codec
 * branching without duplicating the G711 select.
 */
internal fun encodePcmToG711Frame(
    pcm: ShortArray,
    codec: AudioCodec,
    timestampUs: Long,
): AudioFrame {
    val payload = when (codec) {
        AudioCodec.G711A -> G711.encodeAlaw(pcm)
        AudioCodec.G711U -> G711.encodeUlaw(pcm)
        AudioCodec.AAC -> error("encodePcmToG711Frame called with AAC codec")
    }
    return AudioFrame(payload = payload, timestampUs = timestampUs, codec = codec)
}

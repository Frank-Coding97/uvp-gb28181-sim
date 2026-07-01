package com.uvp.sim.camera

import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.G711
import com.uvp.sim.media.MediaTimebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * iOS audio capture + encode.
 *
 * **M1 status**: skeleton. G.711 encoding is pure-common ready (G711.linearToAlaw),
 * but AVAudioEngine tap wiring (`installTapOnBus` uses an ObjC block, needs
 * `staticCFunction` + StableRef bridging identical to VTCompression output
 * callback) is a T2 spike prerequisite.
 *
 * Design when the AVAudioEngine tap lands:
 *
 *   1. AVAudioEngine.inputNode → 16-bit PCM at 8kHz mono
 *   2. installTapOnBus(bufferSize = 160 samples / 20ms)
 *   3. For each buffer: samples → G711.linearToAlaw → AudioFrame emit
 *   4. Timestamp: MediaTimebase.nowUs() at emit time
 *
 * The 20ms frame convention matches Android AndroidAudioStreamer.streamG711
 * for cross-platform RTP packer parity.
 */
class IosAudioStreamer(private val config: AudioCaptureConfig) {

    /**
     * Emit compressed audio frames.
     *
     * Currently returns an empty flow — see class-level TODO. The signature is
     * final; call sites (AudioCapture.ios.kt) can consume the flow safely.
     */
    fun stream(): Flow<AudioFrame> = when (config.codec) {
        AudioCodec.G711A, AudioCodec.G711U -> streamG711()
        AudioCodec.AAC -> streamAac()
    }

    private fun streamG711(): Flow<AudioFrame> = callbackFlow {
        // TODO(T8-follow-up): wire AVAudioEngine + installTapOnBus.
        // Reference impl:
        //   val engine = AVAudioEngine()
        //   val input = engine.inputNode
        //   val format = AVAudioFormat(...)
        //   input.installTapOnBus(0u, 160u, format) { buffer, time ->
        //     val pcm = buffer.toShortArray()
        //     val payload = ByteArray(pcm.size)
        //     for (i in pcm.indices) {
        //       payload[i] = if (config.codec == AudioCodec.G711A)
        //         G711.linearToAlaw(pcm[i].toInt())
        //       else G711.linearToUlaw(pcm[i].toInt())
        //     }
        //     trySend(AudioFrame(payload, MediaTimebase.nowUs(), config.codec))
        //   }
        //   engine.startAndReturnError(null)
        awaitClose { /* engine.stop() */ }
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
        // no-op until AVAudioEngine is wired
    }

    /** Test hook — verify the streamer accepts the configured codec without crashing. */
    fun configuredCodec(): AudioCodec = config.codec
}

/**
 * Encode one 20ms PCM buffer to a G.711 [AudioFrame]. Public utility so the
 * eventual AVAudioEngine tap callback (and future iosTest fixtures) can share
 * the codec branching without duplicating the G711 select.
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

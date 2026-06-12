package com.uvp.sim.camera

import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioFrame
import kotlinx.coroutines.flow.Flow

/**
 * Cross-platform audio capture API mirroring [CameraCapture].
 *
 * - Android implementation uses AudioRecord + software G.711 / hardware AAC encode.
 * - iOS / JVM are stubs — they emit no frames.
 *
 * Output is a Flow of compressed [AudioFrame]s. Codec is determined by [config];
 * the simulator engine subscribes once an INVITE arrives and stops when BYE comes.
 */
expect class AudioCapture(config: AudioCaptureConfig) {
    fun start(): Flow<AudioFrame>
    suspend fun stop()
}

/**
 * Audio capture parameters. Sample rate / channels follow the codec defaults
 * unless overridden — G.711 is fixed at 8 kHz mono, AAC commonly 16 kHz mono.
 */
data class AudioCaptureConfig(
    val codec: AudioCodec = AudioCodec.G711A,
    val sampleRateHz: Int = codec.sampleRateHz,
    val channels: Int = codec.channels,
    /** AAC bitrate; ignored for G.711. */
    val bitrateBps: Int = 32_000
)

package com.uvp.sim.camera

import com.uvp.sim.media.AudioCodec

internal data class EncodingTuning(
    val averageBitRateBps: Int,
    val expectedFrameRate: Int,
    val maxKeyFrameInterval: Int,
)

internal fun encodingTuning(config: CaptureConfig): EncodingTuning = EncodingTuning(
    averageBitRateBps = config.bitrateBps,
    expectedFrameRate = config.frameRate,
    maxKeyFrameInterval = config.frameRate * config.keyframeIntervalSeconds,
)

internal fun targetAudioSampleRate(config: AudioCaptureConfig): Double = when (config.codec) {
    AudioCodec.G711A, AudioCodec.G711U -> 8_000.0
    AudioCodec.AAC -> config.sampleRateHz.toDouble()
}

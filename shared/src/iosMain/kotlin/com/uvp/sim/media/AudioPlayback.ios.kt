package com.uvp.sim.media

/**
 * iOS stub(M3 范围,plan Q6)。真实 AVAudioEngine 实现留 M4(T14)。
 */
actual class AudioPlayback actual constructor(
    @Suppress("UNUSED_PARAMETER") sampleRate: Int,
    @Suppress("UNUSED_PARAMETER") channelCount: Int
) {
    actual fun start() {}
    actual fun write(pcm: ShortArray) {}
    actual fun stop() {}
}

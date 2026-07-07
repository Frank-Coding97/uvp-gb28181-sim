package com.uvp.sim.media

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-E1-0 spike: 证明"合成 alaw 数据 → AVAudioEngine + AVAudioPlayerNode → 扬声器"最短路径通。
 *
 * CI 环境无法真机验证声音,退化为编译 + 无异常 + 输入合法。真机人工听感在 spike 收口
 * (T-E1-5)手动跑,或用 iOS debug 面板按钮触发。
 *
 * 数据:1 秒 440Hz(A4)正弦波 PCM16 单声道 8000Hz,50 帧 × 160 samples/frame。
 */
class AudioPlaybackIosSpikeTest {

    @Test
    fun start_and_feed_alaw_pcm_at_8khz_does_not_throw() {
        val playback = AudioPlayback(sampleRate = 8000, channelCount = 1)

        // start() 一律无抛(runCatching 兜底,GREEN 判定不 crash 即通过)。
        playback.start()

        val frames = synthesizeAlawFrames(sampleRateHz = 8000, seconds = 1, frequencyHz = 440.0)
        assertTrue(frames.size == 50, "expected 50 × 20ms frames, got ${frames.size}")

        // 50 帧 alaw → PCM16 → feed
        for (frame in frames) {
            val pcm = G711.decodeAlaw(frame)
            playback.write(pcm)
        }

        playback.stop()
    }

    private fun synthesizeAlawFrames(sampleRateHz: Int, seconds: Int, frequencyHz: Double): List<ByteArray> {
        val samplesPerFrame = sampleRateHz / 50 // 20ms
        val totalSamples = sampleRateHz * seconds
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val v = sin(2 * PI * frequencyHz * i / sampleRateHz)
            pcm[i] = (v * Short.MAX_VALUE * 0.5).toInt().toShort()
        }
        val frames = mutableListOf<ByteArray>()
        var idx = 0
        while (idx + samplesPerFrame <= pcm.size) {
            val chunk = ShortArray(samplesPerFrame)
            for (i in 0 until samplesPerFrame) chunk[i] = pcm[idx + i]
            frames.add(G711.encodeAlaw(chunk))
            idx += samplesPerFrame
        }
        return frames
    }
}

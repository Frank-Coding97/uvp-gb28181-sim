package com.uvp.sim.media

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-E1-4:G.711A payload(50 帧 × 160 bytes) → alawToLinear → AudioPlayback.write 端到端。
 *
 * 关注点:证明"RTP 收 alaw payload → G711 decode → PCM feed"链路可回归,
 * 无 exception / 无 buffer 尺寸错误。播放正确性(听感)留 T-E1-5 / T-E3-4 真机验。
 */
class G711ToPlayerNodeTest {

    @Test
    fun alaw_frames_decode_to_160_samples_each() {
        val alawFrames = synthesizeAlaw(seconds = 1)
        assertEquals(50, alawFrames.size)
        for (frame in alawFrames) {
            assertEquals(160, frame.size, "each 20ms frame is 160 samples alaw")
            val pcm = G711.decodeAlaw(frame)
            assertEquals(160, pcm.size)
        }
    }

    @Test
    fun feed_50_frames_through_audioplayback_does_not_throw() {
        val playback = AudioPlayback(sampleRate = 8000, channelCount = 1)
        playback.start()
        try {
            for (frame in synthesizeAlaw(seconds = 1)) {
                val pcm = G711.decodeAlaw(frame)
                playback.write(pcm)
            }
        } finally {
            playback.stop()
        }
        assertTrue(true, "50 frames fed through AudioPlayback without throwing")
    }

    private fun synthesizeAlaw(seconds: Int): List<ByteArray> {
        val sr = 8000
        val samplesPerFrame = sr / 50
        val total = sr * seconds
        val pcm = ShortArray(total)
        for (i in 0 until total) {
            pcm[i] = (sin(2 * PI * 440.0 * i / sr) * Short.MAX_VALUE * 0.5).toInt().toShort()
        }
        val out = mutableListOf<ByteArray>()
        var idx = 0
        while (idx + samplesPerFrame <= total) {
            val chunk = ShortArray(samplesPerFrame)
            for (i in 0 until samplesPerFrame) chunk[i] = pcm[idx + i]
            out.add(G711.encodeAlaw(chunk))
            idx += samplesPerFrame
        }
        return out
    }
}

package com.uvp.sim.camera

import com.uvp.sim.media.AudioCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-B2-4:IosAudioStreamer 从抛 Unsupported 改真实实现。
 *
 * Simulator 无麦克风 / 硬件 encoder → 不能真跑 flow;test 只验:
 *   - `configuredCodec()` 返回构造 codec(AAC / G711A 分支公用同一 streamer)
 *   - companion const AAC_SAMPLE_RATE_HZ / AAC_BUFFER_FRAMES 存在且值正确
 *
 * flow emit 真实 AAC 帧的验证留 T-B2-5 集成测 + 真机 T-B4-3。
 */
class IosAudioStreamerAacTest {

    @Test
    fun streamer_accepts_aac_codec_without_crash() {
        val cfg = com.uvp.sim.camera.AudioCaptureConfig(codec = AudioCodec.AAC)
        val streamer = IosAudioStreamer(cfg)
        assertEquals(AudioCodec.AAC, streamer.configuredCodec())
    }

    @Test
    fun streamer_accepts_g711a_codec_regression() {
        val cfg = com.uvp.sim.camera.AudioCaptureConfig(codec = AudioCodec.G711A)
        val streamer = IosAudioStreamer(cfg)
        assertEquals(AudioCodec.G711A, streamer.configuredCodec())
    }

    @Test
    fun companion_const_target_sample_rates() {
        assertEquals(44_100.0, IosAudioStreamer.AAC_SAMPLE_RATE_HZ)
        assertEquals(8_000.0, IosAudioStreamer.SAMPLE_RATE_HZ)
    }

    @Test
    fun companion_const_hw_tap_buffer_frames_reasonable() {
        // 2026-07-09 hw sampleRate 冲突修:tap 用硬件原生格式,bufferSize hint 100ms @ 48kHz。
        assertTrue(IosAudioStreamer.HW_TAP_BUFFER_FRAMES.toInt() in 1024..8192)
    }
}

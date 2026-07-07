package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-B2-1 / T-B2-2:IosAacEncoder 骨架 + encode 累积 → emit AAC 帧行为。
 *
 * iOS Simulator 上 AAC hardware encoder 可能不可用(依赖模拟器 CoreAudio 实现),因此
 * encode 未必 emit 帧 —— test 只 assert:
 *   1. 构造 + close 不 crash(骨架 alive)
 *   2. 累积不够 1024 samples 时返回空 list(不 emit)
 *   3. 累积够 1024 samples 时 encoder init 尝试触发(若 simulator 有 AAC encoder,
 *      至少能验证 API 面 fill buffer 不 crash)
 *
 * 真机 T-B4-3 阶段验完整链路(每帧 payload / PTS / ADTS 头字段等)。
 */
class IosAacEncoderTest {

    @Test
    fun constructor_default_and_close_do_not_crash() {
        val encoder = IosAacEncoder()
        encoder.close()
        // 再 close 一次 idempotent
        encoder.close()
    }

    @Test
    fun encode_less_than_one_frame_returns_empty() {
        val encoder = IosAacEncoder(pcmSampleRateHz = 44_100.0, channelCount = 1u)
        try {
            val pcm = ShortArray(500) // 远小于 1024
            val frames = encoder.encode(pcm, timestampUs = 0L)
            assertEquals(0, frames.size, "累积不够 1024 samples,不应 emit 任何 AAC 帧")
        } finally {
            encoder.close()
        }
    }

    @Test
    fun encode_multiple_chunks_accumulate() {
        val encoder = IosAacEncoder(pcmSampleRateHz = 44_100.0, channelCount = 1u)
        try {
            // 分多次喂,不 crash 即可(Simulator 上 encoder 可能 fail,不 assert emit 数量)
            for (chunk in 0 until 5) {
                val pcm = ShortArray(882) { (kotlin.math.sin(it * 0.1) * 8000).toInt().toShort() }
                val frames = encoder.encode(pcm, timestampUs = chunk * 20_000L)
                // 只 assert 每帧字段结构正确(如果 emit 了)
                for (f in frames) {
                    assertEquals(AudioCodec.AAC, f.codec)
                    assertTrue(f.payload.size >= 7, "ADTS 头至少 7 字节")
                }
            }
        } finally {
            encoder.close()
        }
    }

    @Test
    fun audio_specific_config_returns_null_before_first_encode() {
        val encoder = IosAacEncoder()
        try {
            // converter 未初始化 → 应返回 null
            assertEquals(null, encoder.audioSpecificConfig())
        } finally {
            encoder.close()
        }
    }
}

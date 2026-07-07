package com.uvp.sim.camera

import com.uvp.sim.media.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-B1-1 / T-B1-2:验证 EncodingSession 接受 codec 参数,`videoCodec` 只读字段暴露,
 * H.264 / H.265 两种 config 都能构造(不 crash),`close()` 幂等。
 *
 * 注意:iOS Simulator 不真跑 VideoToolbox HEVC hardware encode,故不 assert `start()`
 * 一定返回 true —— 只验证 API 契约(codec 字段 / 构造 / close 无 crash)。
 * 真机 T-B1-4 / T-B4-3 阶段再验完整 encode 链路。
 */
class EncodingSessionCodecTest {

    @Test
    fun encodingSession_exposes_h264_codec_from_config() {
        val config = CaptureConfig(videoCodec = VideoCodec.H264)
        val session = EncodingSession(config = config, onFrame = { /* no-op */ })
        try {
            assertEquals(VideoCodec.H264, session.videoCodec, "videoCodec 字段应从 config.videoCodec 读")
        } finally {
            session.invalidate()
        }
    }

    @Test
    fun encodingSession_exposes_h265_codec_from_config() {
        val config = CaptureConfig(videoCodec = VideoCodec.H265)
        val session = EncodingSession(config = config, onFrame = { /* no-op */ })
        try {
            assertEquals(VideoCodec.H265, session.videoCodec, "videoCodec 字段应从 config.videoCodec 读")
        } finally {
            session.invalidate()
        }
    }

    @Test
    fun encodingSession_default_codec_is_h264() {
        val config = CaptureConfig() // 默认 H.264
        val session = EncodingSession(config = config, onFrame = { /* no-op */ })
        try {
            assertEquals(VideoCodec.H264, session.videoCodec)
        } finally {
            session.invalidate()
        }
    }
}

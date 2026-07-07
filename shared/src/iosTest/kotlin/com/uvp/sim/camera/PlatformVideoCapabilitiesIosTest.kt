package com.uvp.sim.camera

import com.uvp.sim.media.VideoCodec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-B4-0:iOS 侧 PlatformVideoCapabilities.actual 语义。
 *
 * Simulator 无硬编,`hevcHwEncodeSupported` 大概率 false → 只有 H.264。
 * 真机 A10+ 会返回 `[H264, H265]`。测试只断言:
 *   - H.264 永远在列
 *   - H.264 永远在第一位(fallback 语义)
 *   - 若 supports HEVC 则 [H264, H265]
 */
class PlatformVideoCapabilitiesIosTest {

    @Test
    fun always_contains_h264() {
        val codecs = PlatformVideoCapabilities.supportedVideoCodecs()
        assertContains(codecs, VideoCodec.H264, "H.264 是 iOS 硬件底线,必须永远在列")
    }

    @Test
    fun h264_is_first_for_fallback_semantics() {
        val codecs = PlatformVideoCapabilities.supportedVideoCodecs()
        assertEquals(VideoCodec.H264, codecs.first(), "H.264 优先(fallback 语义)")
    }

    @Test
    fun h265_only_when_hardware_supports_it() {
        val codecs = PlatformVideoCapabilities.supportedVideoCodecs()
        if (VideoCodec.H265 in codecs) {
            assertTrue(
                IosCameraController.hevcHwEncodeSupported,
                "H.265 在列时,硬件探测必须为 true",
            )
        } else {
            // Simulator 或老机型:H.265 不在,codecs 只有 H.264
            assertEquals(listOf(VideoCodec.H264), codecs)
        }
    }
}

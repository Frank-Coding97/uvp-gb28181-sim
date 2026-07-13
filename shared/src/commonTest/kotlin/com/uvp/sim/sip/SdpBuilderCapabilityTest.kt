package com.uvp.sim.sip

import com.uvp.sim.camera.PlatformVideoCapabilities
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoProfile
import com.uvp.sim.media.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-B4-1: SdpBuilder 按 platform capability 过滤 offer 中的 codec。
 *
 * ## 覆盖策略
 *
 * commonTest 里 `PlatformVideoCapabilities` 走 JVM actual(与 Android actual 对齐,
 * 返回 `[H264, H265]`)—— 用于 lock 住"平台能力齐全时,codec 直通不做 fallback"的
 * happy path。真正的 fallback 语义(平台不支持 H.265 时降级到 H.264)在 iOS actual
 * 才生效,由 iosTest `SdpBuilderCapabilityIosTest`(如需要)或真机联调 T-B4-3 覆盖。
 *
 * ## SDP `f=` 行 codec 索引映射(GB28181 §F.1.2)
 *
 * - H.264 → 2
 * - H.265 → 5
 */
class SdpBuilderCapabilityTest {

    private fun cfg(videoCodec: VideoCodec): SimConfig = SimConfig(
        server = ServerConfig(
            ip = "127.0.0.1",
            serverId = "34020000002000000001",
            domain = "3402000000",
        ),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001320000002",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "12345678",
        ),
        video = VideoProfile(videoCodec = videoCodec),
    )

    @Test
    fun platform_capability_actual_contains_both_h264_and_h265_on_jvm() {
        // Sanity guard:JVM/Android actual 都返回 [H264, H265]。若某天有人把 JVM
        // actual 改成 [H264] only,本 test 挂,fallback 相关的其它 assert 也会随之
        // 崩塌 —— 借这条 assert 显式化 "actual 契约"。
        val supported = PlatformVideoCapabilities.supportedVideoCodecs()
        assertTrue(VideoCodec.H264 in supported, "actual 必须支持 H.264")
        assertTrue(VideoCodec.H265 in supported, "commonTest 环境下 actual 必须支持 H.265")
    }

    @Test
    fun h264_config_maps_to_sdp_index_2() {
        val spec = SipHeaderHelpers.buildSdpMediaSpec(cfg(VideoCodec.H264))
        assertEquals(2, spec.videoCodec, "H.264 config → SDP index 2(平台支持,直通)")
    }

    @Test
    fun h265_config_maps_to_sdp_index_5_when_platform_supports_it() {
        // commonTest 环境下 platform 支持 H.265,预期不 fallback。
        val spec = SipHeaderHelpers.buildSdpMediaSpec(cfg(VideoCodec.H265))
        assertEquals(5, spec.videoCodec, "H.265 config + 平台支持 → SDP index 5(不 fallback)")
    }
}

package com.uvp.sim.camera

import com.uvp.sim.media.VideoCodec

/**
 * JVM 侧 actual — 无媒体线,commonTest 场景对 SDP capability 过滤只做形式验证,
 * 与 Android actual 对齐返回 `[H264, H265]`,让 commonTest 的 SdpBuilder 路径
 * (含 H.265 fmt=5)不会被 JVM 侧空能力集意外挡回 H.264。
 *
 * T-B4-1(iOS v1.3-B):真实 capability 语义只在 iOS actual 中生效(按 A9/A10+ 硬件探测);
 * JVM/Android 假设平台已装能力齐全的编码器。
 */
actual object PlatformVideoCapabilities {
    actual fun supportedVideoCodecs(): List<VideoCodec> =
        listOf(VideoCodec.H264, VideoCodec.H265)
}

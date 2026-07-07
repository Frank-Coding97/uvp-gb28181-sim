package com.uvp.sim.camera

import com.uvp.sim.media.VideoCodec

/**
 * JVM 侧 actual — 无媒体线,只返回名义 H.264(测试用途)。
 */
actual object PlatformVideoCapabilities {
    actual fun supportedVideoCodecs(): List<VideoCodec> = listOf(VideoCodec.H264)
}

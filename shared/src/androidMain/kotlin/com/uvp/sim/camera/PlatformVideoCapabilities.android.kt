package com.uvp.sim.camera

import com.uvp.sim.media.VideoCodec

/**
 * Android 侧 actual — 硬件基本盘足以覆盖 H.264 + H.265,不做运行时探测。
 * 若极端老机型硬编 H.265 fail,由 MediaCodec 创建阶段抛错自然回退。
 */
actual object PlatformVideoCapabilities {
    actual fun supportedVideoCodecs(): List<VideoCodec> =
        listOf(VideoCodec.H264, VideoCodec.H265)
}

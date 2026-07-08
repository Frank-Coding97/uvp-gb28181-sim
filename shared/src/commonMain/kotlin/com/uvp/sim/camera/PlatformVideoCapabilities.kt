package com.uvp.sim.camera

import com.uvp.sim.media.VideoCodec

/**
 * 平台侧视频编码能力查询。
 *
 * SDP capability advertise 层调用它决定 offer 哪些 codec:
 * - Android:硬编 H.264/H.265 均支持,永远返回 [VideoCodec.H264, VideoCodec.H265]
 * - iOS:H.264 恒真,H.265 依赖硬编探测(A9 只解不编,A10+ 才能硬编)
 *
 * T-B4-0(iOS v1.3-B):spec Q7 / plan §3.1.2 决策 —— 启动时探测 + SDP 不 offer 不支持的 codec,
 * 不做运行时 fallback。
 */
expect object PlatformVideoCapabilities {
    /**
     * 返回按优先级降序排列的视频 codec 列表(H.264 优先兜底,H.265 若支持则并列)。
     * 顺序稳定,SDP 层挑第一项作为 default answer。
     */
    fun supportedVideoCodecs(): List<VideoCodec>
}

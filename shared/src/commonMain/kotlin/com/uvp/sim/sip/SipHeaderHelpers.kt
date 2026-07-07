package com.uvp.sim.sip

import com.uvp.sim.config.SimConfig

/**
 * 公共 SIP header / SDP 解析 helper —— 抽自原先分散在
 * Playback/Invite/Broadcast/ManscdpRouter 4 个 Coord 的私有重复实现
 * (codex audit P2-5)。
 *
 * 设计要点:
 * - 纯字符串处理 / 纯 config 投影,**无副作用**、**无外部依赖**
 * - `internal` 可见性,仅供 sim 内部使用,不暴露公共 API
 * - 实现按各 Coord 原始版本保持 byte-equivalent —— 不在重构里偷偷改语义
 *
 * 私有 parseUriHost(InviteCoord 独有,只 1 处使用)未上抽,留在原文件。
 */
internal object SipHeaderHelpers {

    /**
     * 提取 SIP header 里的 URI 主体。
     *
     * 支持两种形态:
     *  - `<sip:user@host:port>;tag=xxx` -> 返回 `sip:user@host:port`
     *  - `sip:user@host:port;tag=xxx`   -> 返回 `sip:user@host:port`(取首个 `;` 之前)
     *
     * @return URI 主体字符串,失败/空输入返回空串
     */
    fun parseUri(headerValue: String): String {
        val lt = headerValue.indexOf('<')
        val gt = headerValue.indexOf('>')
        return if (lt >= 0 && gt > lt) headerValue.substring(lt + 1, gt)
        else headerValue.substringBefore(';').trim()
    }

    /**
     * 从 SIP header(From/To)中提取 `;tag=` 参数值。
     *
     * @return tag 值;若 header 不含 tag 参数则返回空串。
     */
    fun parseTag(headerValue: String): String {
        val idx = headerValue.indexOf(";tag=")
        if (idx < 0) return ""
        val rest = headerValue.substring(idx + 5)
        val end = rest.indexOfAny(charArrayOf(';', ' ', '>', '\r', '\n'))
        return if (end < 0) rest else rest.substring(0, end)
    }

    /**
     * 从 SIP URI(`sip:user@host[:port][;params]`)提取 user 部分。
     *
     * @param uri SIP URI(已剥离尖括号)
     * @param fallback 当 URI 不含 user 段时返回的默认值(典型为 `config.server.serverId`)
     */
    fun parseUriUser(uri: String, fallback: String = ""): String {
        val s = uri.substringAfter("sip:", uri).substringBefore('@', "")
        return s.ifEmpty { fallback }
    }

    /**
     * 把 [SimConfig.video] 投影成 GB28181 SDP `f=` 行所需的 [SdpAnswer.MediaSpec]。
     *
     * 编码映射(GB28181 §F.1.2 SDP `f=` 字段):
     *  - videoCodec: H264=2, H265=5
     *  - resolution: SD_480P=4, HD_720P=5, FHD_1080P=6
     *  - audioCodec: G711A=1, G711U=2, AAC=11
     *  - audioBitrateKbps: G711 系=64, AAC=32
     *  - audioSampleRate: 8k=1, 14k=2, 16k=3, 32k=4(默认 16k)
     *  - rateType: 固定 2(VBR)
     */
    fun buildSdpMediaSpec(config: SimConfig): SdpAnswer.MediaSpec {
        val v = config.video
        // T-B4-1:按 platform capability 过滤 offer 中的 codec —— 硬件不支持 H.265 时降级到 H.264,
        //         避免推流到 WVP 后收方无法解码。capability set 从 PlatformVideoCapabilities 取。
        val supported = com.uvp.sim.camera.PlatformVideoCapabilities.supportedVideoCodecs()
        val effectiveVideoCodec = if (v.videoCodec in supported) {
            v.videoCodec
        } else {
            // Fallback:capability 交集里挑第一项(H.264 优先)。
            val fallback = supported.firstOrNull() ?: com.uvp.sim.media.VideoCodec.H264
            fallback
        }
        val videoCodec = when (effectiveVideoCodec) {
            com.uvp.sim.media.VideoCodec.H264 -> 2
            com.uvp.sim.media.VideoCodec.H265 -> 5
        }
        val resolution = when (v.resolution) {
            com.uvp.sim.config.VideoResolution.SD_480P -> 4
            com.uvp.sim.config.VideoResolution.HD_720P -> 5
            com.uvp.sim.config.VideoResolution.FHD_1080P -> 6
        }
        val audioCodec = when (v.audioCodec) {
            com.uvp.sim.media.AudioCodec.G711A -> 1
            com.uvp.sim.media.AudioCodec.G711U -> 2
            com.uvp.sim.media.AudioCodec.AAC -> 11
        }
        val audioBitrateKbps = when (v.audioCodec) {
            com.uvp.sim.media.AudioCodec.G711A,
            com.uvp.sim.media.AudioCodec.G711U -> 64
            com.uvp.sim.media.AudioCodec.AAC -> 32
        }
        val audioSampleRate = when (v.effectiveAudioSampleRateHz) {
            8_000 -> 1
            14_000 -> 2
            16_000 -> 3
            32_000 -> 4
            else -> 3
        }
        return SdpAnswer.MediaSpec(
            videoCodec = videoCodec,
            resolution = resolution,
            frameRate = v.frameRate,
            rateType = 2,
            videoBitrateKbps = v.bitrateKbps,
            audioCodec = audioCodec,
            audioBitrateKbps = audioBitrateKbps,
            audioSampleRate = audioSampleRate,
        )
    }
}

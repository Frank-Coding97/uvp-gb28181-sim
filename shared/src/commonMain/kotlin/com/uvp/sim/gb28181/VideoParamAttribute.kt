package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig
import com.uvp.sim.media.VideoCodec
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger

/**
 * GB/T 28181-2022 A.2.1.13 `VideoParamAttribute` —— 视频参数的**当前取值**配置。
 *
 * ⛔ **与 [ConfigDownloadResponse] 里的 `VideoParamOpt`(A.2.1.20) 是两件事,别混**:
 *   - `VideoParamOpt` = 摄像机**支持**的范围(多值),2016 就有
 *   - `VideoParamAttribute` = **当前生效**的取值(逐码流一条),**2022 新增**
 *
 * 两条出口:
 *   - **读**:平台 `<Query><CmdType>ConfigDownload</CmdType><ConfigType>VideoParamAttribute</ConfigType>`
 *     → 设备回 `<Response>…<VideoParamAttribute Num="N"><Item>…`(见 [renderBlock])
 *   - **写**:平台 `<Control><CmdType>DeviceConfig</CmdType><VideoParamAttribute>…`
 *     → 设备落 [com.uvp.sim.domain.DeviceControlModel.videoParams](见 [parseItems])
 *
 * ⚠️ **口径边界(刻意为之,别当成 bug)**:本模拟器只把这份配置**记账**,
 * **不真去改手机摄像头的编码参数**。手机实际出流仍由
 * [com.uvp.sim.config.VideoProfile] + CameraX 决定。
 * 所以「平台下发 1080P → 回读 1080P」证明的是**平台侧那条链(构造 → 下发 → 回读 → 对账)通了**,
 * 不是画面真的换了分辨率。真 IPC 上这两件事是同一件。
 */
data class VideoParamState(
    /** 0 = 主码流;1 = 子码流 1… 标准明文。落库唯一键与面板分段都靠它。 */
    val streamNumber: Int,
    val videoFormat: String,
    val resolution: String,
    val frameRate: String,
    val bitRateType: String,
    /**
     * 视频码率(kb/s)。**条件必选**:标准明文「固定码率时必选」→ 仅 CBR 时出现。
     *
     * ⛔ `null` = 「报文里**没有这个元素**」,与 `"0"`(真的配了 0 kb/s)是两件事。
     * 回读对账时这两者要能分开,否则 VBR 配置会被显示成「码率 0」。
     */
    val videoBitRate: String? = null,
)

/**
 * `VideoParamAttribute` 的取值表、默认值与线格式。
 *
 * 抽成 object 而不是散在 builder/parser 里,理由同 `VideoResolution.gb28181Code`:
 * 同一张码表被两处各写一遍,迟早分家(T1 真踩过:SDP 发码值、ConfigDownload 发人读串)。
 */
object VideoParamAttribute {

    /** 平台请求/应答里用的配置类型名(`ConfigType` 元素取值)。**单数**,不是 `VideoParamAttributeList`。 */
    const val CONFIG_TYPE = "VideoParamAttribute"

    // ---- 附录 G(标准页 130)`f=v/编码格式/分辨率/帧率/码率类型/码率大小` 的码值 ----
    // 发的是**数字字符串**,不是 "H.264" / "720P" / "CBR" 这些人读串(人读串只允许出现在 UI 层)。
    const val VIDEO_FORMAT_MPEG4 = "1"
    const val VIDEO_FORMAT_H264 = "2"
    const val VIDEO_FORMAT_SVAC = "3"
    const val VIDEO_FORMAT_3GP = "4"
    const val VIDEO_FORMAT_H265 = "5"

    const val BIT_RATE_TYPE_CBR = "1"
    const val BIT_RATE_TYPE_VBR = "2"

    /**
     * 解析目录 `Info/StreamNumberList`(`"0/1"`)→ `[0, 1]`(去重、升序)。
     *
     * ⭐ **必须与 [CatalogNotifyBuilder] 输出的是同一个字段**:设备在目录里声明支持几段码流,
     * 配置回读就得按同样几段作答。两处不一致的后果不是"少显示一个数",而是平台面板
     * 按目录声明渲染 N 行、回读只有 M 行,看起来像「设备漏答了」。
     *
     * 空串 / 全是非法值 → `[0]`:一个摄像机至少有一条主码流,这是设备的**物理事实**,
     * 不是"给个兜底默认值"。(目录侧对空串的做法是干脆不发该元素,见 `appendInfoElement`。)
     */
    fun streamNumbersOf(spec: String): List<Int> =
        spec.split('/')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }
            .distinct()
            .sorted()
            .ifEmpty { listOf(0) }

    /** [VideoCodec] → 附录 G 的 `VideoFormat` 码值(2=H.264 / 5=H.265)。 */
    fun videoFormatCodeOf(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> VIDEO_FORMAT_H264
        VideoCodec.H265 -> VIDEO_FORMAT_H265
    }

    /**
     * 平台从未下发过时,设备对外报的**出厂默认** —— 直接从 [SimConfig.video] 派生,
     * 与设备实际出流用的参数同源(分辨率 / 帧率 / 码率 / 编码器)。
     *
     * ⛔ **为什么不能"没配置过就回空"**:回读为空 == 平台判据里的 `type_absent`
     * (「设备不支持该配置类型」)。真实设备一上电就有编码配置,不可能是空的 ——
     * 回空会把「这台设备支持该类型、只是没人配过」误报成「设备不支持」,而这正是
     * A-5 设计里最忌讳的假阴性(见平台侧
     * `UVP-GB28181/docs/gb28181-2022-video-param-attribute-panel.md` §十④)。
     *
     * ⛔ **子码流(`streamNumber > 0`)自 2026-09-19 起有独立档位**（见本函数内联的降档规则）。
     * 在那之前它返回与主码流**完全一样**的配置，因为 [com.uvp.sim.config.VideoProfile] 只有一份。
     * 后果不是"少一个数"：平台面板上主/子两行的分辨率、帧率、码率**逐格相同**，
     * 看起来像"设备把子码流配成了主码流的副本"，而真实子码流永远是更低档 ——
     * 「主子码流分别配置」这件事在界面上根本演不出来。
     */
    fun defaultFor(config: SimConfig, streamNumber: Int): VideoParamState {
        val video = config.video
        if (streamNumber <= 0) {
            return VideoParamState(
                streamNumber = 0,
                videoFormat = videoFormatCodeOf(video.videoCodec),
                resolution = video.resolution.gb28181Code.toString(),
                frameRate = video.frameRate.toString(),
                bitRateType = BIT_RATE_TYPE_CBR,
                videoBitRate = video.bitrateKbps.toString(),
            )
        }
        // 子码流：分辨率按 VideoResolution 的档位序向下退 streamNumber 级（退到底就停在最低档）。
        val ladder = com.uvp.sim.config.VideoResolution.entries
        val mainIndex = ladder.indexOf(video.resolution).coerceAtLeast(0)
        val subResolution = ladder[(mainIndex - streamNumber).coerceAtLeast(0)]
        return VideoParamState(
            streamNumber = streamNumber,
            videoFormat = videoFormatCodeOf(video.videoCodec),
            resolution = subResolution.gb28181Code.toString(),
            frameRate = SUB_STREAM_MAX_FRAME_RATE.coerceAtMost(video.frameRate).toString(),
            bitRateType = BIT_RATE_TYPE_CBR,
            videoBitRate = (video.bitrateKbps / SUB_STREAM_BITRATE_DIVISOR)
                .coerceAtLeast(SUB_STREAM_MIN_BITRATE_KBPS)
                .toString(),
        )
    }

    /**
     * 子码流默认档位的三个常量 —— 本仓口径（**标准不规定子码流该是多少**，真机由厂商定）。
     *
     * 定这三个数的依据是"行业内子码流长什么样"：分辨率比主码流低一档起、帧率封顶 15、
     * 码率约主码流的四分之一。它们是**确定且可解释**的，回读验收时"主/子两行不同"这件事
     * 才可复现；随手拍一个数会让验收基线每次都不一样。
     */
    private const val SUB_STREAM_MAX_FRAME_RATE = 15
    private const val SUB_STREAM_BITRATE_DIVISOR = 4
    private const val SUB_STREAM_MIN_BITRATE_KBPS = 256

    /**
     * 设备**当前生效**的全部码流配置 = 目录声明的码流号 × (平台写入值 ?: 出厂默认)。
     *
     * 这是回读应答的唯一数据源,[renderBlock] 只负责拼字符串。
     *
     * ⚠️ 取的是**并集**:平台若写了设备没在目录里声明的码流号,设备**照记照回**,不静默丢弃。
     * 丢弃会让平台看到「写进去了、回读没有」→ 误判成设备能力不足;
     * 而模拟器压根没有"最多几路编码器"这个真实约束,不该凭空造一个出来。
     */
    fun effectiveParams(
        config: SimConfig,
        overrides: Map<Int, VideoParamState>,
    ): List<VideoParamState> =
        (streamNumbersOf(config.device.channel.streamNumberList) + overrides.keys)
            .distinct()
            .sorted()
            .map { streamNumber -> overrides[streamNumber] ?: defaultFor(config, streamNumber) }

    /**
     * 解析写入报文里的全部 `Item`。
     *
     * ⛔ **不能用 [ManscdpParser.tagValue]** —— 它只取**第一个**匹配,而标准里
     * `Item` 是 `maxOccurs="unbounded"`(与存储卡的 `maxOccurs=8` 不同),逐个取才完整。
     * 只取第一个的后果:平台下发主码流 + 子码流两条,设备只认了主码流一条,
     * 回读少一条而**看不出哪里错**。
     *
     * ⛔ `StreamNumber` 缺失或非法的 `Item` **整条跳过并记 warn**,绝不补 0:
     * 补 0 等于把一条坏数据悄悄写成「主码流配置」,把平台的一条报文错误
     * 变成设备侧的一个错误配置。
     *
     * ⛔ 取值**不做范围校验**(宽松收):设备回给自己前先照单收下,范围是否合法属于
     * 平台侧"严格发"的职责。这里只保证**结构**(StreamNumber 必须能比较)。
     */
    fun parseItems(xml: String): List<VideoParamState> {
        val region = itemsRegion(xml) ?: return emptyList()
        val parsed = mutableListOf<VideoParamState>()
        var cursor = 0
        while (true) {
            val open = region.indexOf("<Item", cursor)
            if (open < 0) break
            val openEnd = region.indexOf('>', open)
            if (openEnd < 0) break
            val selfClosing = region[openEnd - 1] == '/'
            val close = region.indexOf("</Item>", openEnd)
            if (selfClosing || close < 0) {
                // 空 <Item/> 或结构坏了:跳过这一处,继续找后面的(别让一条坏数据吃掉整份报文)。
                cursor = openEnd + 1
                continue
            }
            val body = region.substring(openEnd + 1, close)
            cursor = close + CLOSE_ITEM.length

            val streamNumber = ManscdpParser.tagValue(body, "StreamNumber")?.trim()?.toIntOrNull()
            if (streamNumber == null || streamNumber < 0) {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Network,
                    "VideoParamAttribute: 跳过一条缺少合法 StreamNumber 的 Item"
                )
                continue
            }
            val bitRate = ManscdpParser.tagValue(body, "VideoBitRate")?.trim()
            parsed += VideoParamState(
                streamNumber = streamNumber,
                videoFormat = subValue(body, "VideoFormat"),
                resolution = subValue(body, "Resolution"),
                frameRate = subValue(body, "FrameRate"),
                bitRateType = subValue(body, "BitRateType"),
                // 空元素与缺席一律按"没有这个元素"处理 —— 条件必选的语义就是这样。
                videoBitRate = bitRate?.takeIf { it.isNotEmpty() },
            )
        }
        return parsed
    }

    /**
     * 拼回读应答里的 `<VideoParamAttribute>` 块。
     *
     * ⛔ `Num` 是**属性**不是子元素(标准把它挂在 complexType 上)。写成 `<Num>` 平台侧的
     * `xml:"Num,attr"` 解不出来,**且不报错** —— 表现为「设备回了 OK 但没数据」。
     * 这条同平台侧 `videoParamAttributeXML.Num` 的注释,两侧对同一个坑。
     *
     * ⛔ `VideoBitRate` 仅在 CBR 时输出;VBR 时**整个元素不出现**(条件必选)。
     * 不能输出空元素 `<VideoBitRate></VideoBitRate>` —— 平台侧会把它解成空串,
     * 与"没这个元素"含义不同(前者会被当成"给了一个空值")。
     */
    fun renderBlock(states: List<VideoParamState>): String {
        val items = buildString {
            for (state in states) {
                append("<Item>")
                append("<StreamNumber>").append(state.streamNumber).append("</StreamNumber>")
                append("<VideoFormat>").append(state.videoFormat).append("</VideoFormat>")
                append("<Resolution>").append(state.resolution).append("</Resolution>")
                append("<FrameRate>").append(state.frameRate).append("</FrameRate>")
                append("<BitRateType>").append(state.bitRateType).append("</BitRateType>")
                state.videoBitRate?.let {
                    append("<VideoBitRate>").append(it).append("</VideoBitRate>")
                }
                append("</Item>")
            }
        }
        return """<VideoParamAttribute Num="${states.size}">$items</VideoParamAttribute>
"""
    }

    /** 取 `<VideoParamAttribute …>…</VideoParamAttribute>` 之间的内容;元素缺席返回 null。 */
    private fun itemsRegion(xml: String): String? {
        val open = xml.indexOf("<$CONFIG_TYPE")
        if (open < 0) return null
        val openEnd = xml.indexOf('>', open)
        if (openEnd < 0) return null
        val close = xml.indexOf("</$CONFIG_TYPE>", openEnd)
        if (close < 0) return null
        return xml.substring(openEnd + 1, close)
    }

    /** 取子元素值并 trim;缺席返回空串(与"给了个空值"在设备侧不做区分,如实记账)。 */
    private fun subValue(body: String, tag: String): String =
        ManscdpParser.tagValue(body, tag)?.trim().orEmpty()

    private const val CLOSE_ITEM = "</Item>"
}

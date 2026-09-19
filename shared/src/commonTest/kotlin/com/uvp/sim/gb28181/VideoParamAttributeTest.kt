package com.uvp.sim.gb28181

import com.uvp.sim.config.ChannelProfile
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoProfile
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.media.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A.2.1.13 `VideoParamAttribute` 的取值表 / 默认值 / 线格式。
 *
 * 这类用例的价值都在**否定式断言**上:它们守的是"不该出现的形态"——
 * `Num` 不能是子元素、VBR 不能带码率、多条 `Item` 不能被只取第一条吃掉。
 */
class VideoParamAttributeTest {

    private fun config(
        resolution: VideoResolution = VideoResolution.FHD_1080P,
        frameRate: Int = 25,
        bitrateKbps: Int = 2000,
        codec: VideoCodec = VideoCodec.H264,
        streamNumberList: String = "0",
    ) = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password",
            channel = ChannelProfile(streamNumberList = streamNumberList),
        ),
        video = VideoProfile(
            resolution = resolution,
            frameRate = frameRate,
            bitrateKbps = bitrateKbps,
            videoCodec = codec,
        ),
    )

    // ===== 码流清单:必须与目录 Info/StreamNumberList 同源 =====

    @Test fun streamNumbersOf_splitsDedupesAndSorts() {
        assertEquals(listOf(0), VideoParamAttribute.streamNumbersOf("0"))
        assertEquals(listOf(0, 1), VideoParamAttribute.streamNumbersOf("0/1"))
        assertEquals(listOf(0, 1), VideoParamAttribute.streamNumbersOf("1/0"), "要升序")
        assertEquals(listOf(0, 1), VideoParamAttribute.streamNumbersOf("0/0/1"), "要去重")
        assertEquals(listOf(0, 1, 2), VideoParamAttribute.streamNumbersOf(" 0 / 1 / 2 "))
    }

    @Test fun streamNumbersOf_fallsBackToMainStreamOnly() {
        // 一个摄像机至少有一条主码流 —— 这是设备的物理事实,不是"兜底默认值"。
        assertEquals(listOf(0), VideoParamAttribute.streamNumbersOf(""))
        assertEquals(listOf(0), VideoParamAttribute.streamNumbersOf("abc"))
        assertEquals(listOf(0), VideoParamAttribute.streamNumbersOf("-1/xyz"))
        assertEquals(listOf(1), VideoParamAttribute.streamNumbersOf("abc/1"), "合法项要留下")
    }

    // ===== 出厂默认:派生自 SimConfig.video,与设备实际出流同源 =====

    @Test fun defaultFor_derivesAppendixGCodesFromVideoProfile() {
        val state = VideoParamAttribute.defaultFor(config(), streamNumber = 0)
        assertEquals(0, state.streamNumber)
        assertEquals("2", state.videoFormat, "H.264 = 附录 G 码值 2")
        assertEquals("6", state.resolution, "1080P = 附录 G 码值 6")
        assertEquals("25", state.frameRate)
        assertEquals("1", state.bitRateType, "出厂按固定码率")
        assertEquals("2000", state.videoBitRate)
    }

    @Test fun defaultFor_mapsCodecAndResolutionLadder() {
        val h265 = VideoParamAttribute.defaultFor(config(codec = VideoCodec.H265), 0)
        assertEquals("5", h265.videoFormat, "H.265 = 附录 G 码值 5")

        val sd = VideoParamAttribute.defaultFor(config(resolution = VideoResolution.SD_480P), 0)
        assertEquals("4", sd.resolution)
        // 单一真源:SDP / VideoParamOpt / VideoParamAttribute 三处必须都出自
        // VideoResolution.gb28181Code,否则同一台设备的三条出口各说各话。
        assertEquals(VideoResolution.SD_480P.gb28181Code.toString(), sd.resolution)
    }

    // ===== 生效值 = 目录声明码流 × (平台写入 ?: 出厂默认) =====

    @Test fun effectiveParams_neverEmptyEvenWithoutPlatformWrite() {
        val params = VideoParamAttribute.effectiveParams(config(), emptyMap())
        assertEquals(1, params.size)
        assertEquals(0, params.single().streamNumber)
    }

    @Test fun effectiveParams_overrideWinsPerStream() {
        val written = VideoParamState(
            streamNumber = 0, videoFormat = "5", resolution = "5",
            frameRate = "30", bitRateType = "1", videoBitRate = "8192",
        )
        val params = VideoParamAttribute.effectiveParams(
            config(streamNumberList = "0/1"), mapOf(0 to written)
        )
        assertEquals(listOf(0, 1), params.map { it.streamNumber })
        assertEquals(written, params.first { it.streamNumber == 0 })
        // 平台没配过的那一路退回出厂默认 —— 不是缺席,更不是一个空 Item。
        assertEquals(
            VideoParamAttribute.defaultFor(config(streamNumberList = "0/1"), 1),
            params.first { it.streamNumber == 1 },
        )
    }

    @Test fun effectiveParams_keepsStreamNumbersTheDeviceNeverDeclared() {
        // ⛔ 不静默丢弃:丢弃会让平台看到"写进去了、回读没有" → 误判成设备能力不足。
        //    模拟器没有"最多几路编码器"这个真实约束,不该凭空造一个。
        val written = VideoParamState(
            streamNumber = 3, videoFormat = "2", resolution = "6",
            frameRate = "25", bitRateType = "1", videoBitRate = "4096",
        )
        val params = VideoParamAttribute.effectiveParams(config(streamNumberList = "0"), mapOf(3 to written))
        assertEquals(listOf(0, 3), params.map { it.streamNumber })
    }

    // ===== 子码流出厂默认:必须与主码流**不同档**（2026-09-19 起） =====

    /**
     * ⭐ 这一组守的是"主子码流分别配置"这件事**在界面上演得出来**。
     *
     * 在此之前子码流返回与主码流**完全一样**的配置（[VideoProfile] 只有一份），
     * 后果不是"少一个数"：平台面板上主/子两行的分辨率、帧率、码率**逐格相同**，
     * 看起来像"设备把子码流配成了主码流的副本"。
     *
     * ⚠️ 标准**不规定**子码流该是多少（真机由厂商定），所以这里的期望值锁的是本仓
     * **口径常量**：向下退一级分辨率的档位 / 帧率封顶 15 / 码率取主码流的四分之一。
     * 改常量就要同步改这里 —— 这正是"验收基线可复现"要付的代价。
     */
    @Test fun defaultFor_subStreamStepsDownOneResolutionStep() {
        val main = VideoParamAttribute.defaultFor(config(streamNumberList = "0/1"), 0)
        val sub = VideoParamAttribute.defaultFor(config(streamNumberList = "0/1"), 1)
        assertEquals("6", main.resolution, "1080P = 附录 G 码值 6")
        assertEquals("5", sub.resolution, "1080P 向下退一级 = 720P")
        assertEquals("25", main.frameRate)
        assertEquals("15", sub.frameRate, "子码流帧率封顶 15")
        assertEquals("2000", main.videoBitRate)
        assertEquals("500", sub.videoBitRate, "码率取主码流的四分之一")
        assertEquals(1, sub.streamNumber)
        assertEquals("1", sub.bitRateType, "子码流同样按固定码率")
    }

    @Test fun defaultFor_subStreamNeverRaisesFrameRateAboveMain() {
        // 主码流本来就只有 10fps 时，子码流不能反而"更高"。
        assertEquals("10", VideoParamAttribute.defaultFor(config(frameRate = 10), 1).frameRate)
    }

    @Test fun defaultFor_subStreamBitrateHasFloor() {
        // 400 / 4 = 100 < 256 ⇒ 停在下限，不让子码流码率低到不可用。
        assertEquals("256", VideoParamAttribute.defaultFor(config(bitrateKbps = 400), 1).videoBitRate)
    }

    @Test fun defaultFor_subStreamStopsAtLowestLadderStep() {
        // 退到底就停在最低档，不越界（档位表只有 3 级）。
        assertEquals("4", VideoParamAttribute.defaultFor(config(resolution = VideoResolution.SD_480P), 2).resolution)
    }

    /** 面板按目录声明的码流数渲染 N 行，回读就得有 N 行、且行与行**不一样**。 */
    @Test fun effectiveParams_mainAndSubRowsAreDistinguishable() {
        val params = VideoParamAttribute.effectiveParams(config(streamNumberList = "0/1"), emptyMap())
        assertEquals(listOf(0, 1), params.map { it.streamNumber })
        assertNotEquals(params[0].resolution, params[1].resolution)
        assertNotEquals(params[0].frameRate, params[1].frameRate)
        assertNotEquals(params[0].videoBitRate, params[1].videoBitRate)
    }

    // ===== 写入解析 =====

    private fun writeXml(streams: String) =
        "<Control><CmdType>DeviceConfig</CmdType><SN>7</SN>" +
            "<DeviceID>34020000001110000001</DeviceID>" +
            "<VideoParamAttribute Num=\"${streams.split("</Item>").size - 1}\">$streams</VideoParamAttribute>" +
            "</Control>"

    @Test fun parseItems_readsEveryItemNotJustTheFirst() {
        // ⛔ 这条用例存在的唯一理由:`ManscdpParser.tagValue` 只取**第一个**匹配。
        //    若实现偷懒用了它,平台下发主码流+子码流时设备只认一条,
        //    回读少一条而**看不出哪里错**(平台侧表现为"设备只回了一路")。
        val xml = writeXml(
            "<Item><StreamNumber>0</StreamNumber><VideoFormat>2</VideoFormat>" +
                "<Resolution>6</Resolution><FrameRate>25</FrameRate>" +
                "<BitRateType>1</BitRateType><VideoBitRate>4096</VideoBitRate></Item>" +
                "<Item><StreamNumber>1</StreamNumber><VideoFormat>2</VideoFormat>" +
                "<Resolution>5</Resolution><FrameRate>15</FrameRate>" +
                "<BitRateType>1</BitRateType><VideoBitRate>1024</VideoBitRate></Item>"
        )
        val items = VideoParamAttribute.parseItems(xml)
        assertEquals(listOf(0, 1), items.map { it.streamNumber })
        assertEquals("6", items[0].resolution)
        assertEquals("15", items[1].frameRate)
        assertEquals("1024", items[1].videoBitRate)
    }

    @Test fun parseItems_conditionsOptionalBitRate() {
        // CBR 带码率 → 有值
        val cbr = VideoParamAttribute.parseItems(
            writeXml(
                "<Item><StreamNumber>0</StreamNumber><VideoFormat>2</VideoFormat>" +
                    "<Resolution>6</Resolution><FrameRate>25</FrameRate>" +
                    "<BitRateType>1</BitRateType><VideoBitRate>4096</VideoBitRate></Item>"
            )
        ).single()
        assertEquals("4096", cbr.videoBitRate)

        // VBR 不带码率元素 → null(不是 ""、更不是 "0")
        val vbr = VideoParamAttribute.parseItems(
            writeXml(
                "<Item><StreamNumber>0</StreamNumber><VideoFormat>2</VideoFormat>" +
                    "<Resolution>6</Resolution><FrameRate>25</FrameRate>" +
                    "<BitRateType>2</BitRateType></Item>"
            )
        ).single()
        assertNull(vbr.videoBitRate, "VBR 时该元素本就该缺席")

        // 空元素与缺席同义 —— 都按"没有这个元素"处理
        val emptyElement = VideoParamAttribute.parseItems(
            writeXml(
                "<Item><StreamNumber>0</StreamNumber><VideoFormat>2</VideoFormat>" +
                    "<Resolution>6</Resolution><FrameRate>25</FrameRate>" +
                    "<BitRateType>2</BitRateType><VideoBitRate></VideoBitRate></Item>"
            )
        ).single()
        assertNull(emptyElement.videoBitRate)
    }

    @Test fun parseItems_skipsBadItemButKeepsTheRest() {
        // 缺 StreamNumber 的那条整条跳过(绝不补 0 —— 补 0 等于把坏数据写成"主码流配置"),
        // 但**不能连坐**后面的好数据。
        val xml = writeXml(
            "<Item><VideoFormat>2</VideoFormat><Resolution>6</Resolution></Item>" +
                "<Item><StreamNumber>1</StreamNumber><VideoFormat>2</VideoFormat>" +
                "<Resolution>5</Resolution><FrameRate>15</FrameRate>" +
                "<BitRateType>1</BitRateType><VideoBitRate>1024</VideoBitRate></Item>"
        )
        assertEquals(listOf(1), VideoParamAttribute.parseItems(xml).map { it.streamNumber })
    }

    @Test fun parseItems_absenceAndEmptyConfigAreBothEmptyLists() {
        assertEquals(emptyList(), VideoParamAttribute.parseItems("<Control><CmdType>DeviceConfig</CmdType></Control>"))
        assertEquals(
            emptyList(),
            VideoParamAttribute.parseItems("<VideoParamAttribute Num=\"0\"></VideoParamAttribute>"),
            "空配置合法(minOccurs=0),解析成空列表而不是报错",
        )
    }

    // ===== 回读渲染 =====

    @Test fun renderBlock_numIsAttributeAndVbrOmitsBitRate() {
        val block = VideoParamAttribute.renderBlock(
            listOf(
                VideoParamState(0, "2", "6", "25", "1", "4096"),
                VideoParamState(1, "2", "5", "15", "2", null),
            )
        )
        assertTrue(block.contains("Num=\"2\""), "actual: $block")
        assertFalse(block.contains("<Num>"), "Num 必须是属性")
        assertTrue(block.contains("<VideoBitRate>4096</VideoBitRate>"))
        // ⛔ 只有一条 VideoBitRate —— VBR 那路不能输出空元素
        assertEquals(2, block.split("<BitRateType>").size - 1)
        assertEquals(1, block.split("<VideoBitRate>").size - 1)
        assertFalse(block.contains("<VideoBitRate></VideoBitRate>"))
    }

    @Test fun renderBlock_emptyStatesStillHasNumAttribute() {
        val block = VideoParamAttribute.renderBlock(emptyList())
        assertTrue(block.contains("Num=\"0\""))
        assertFalse(block.contains("<Item>"))
    }

    /**
     * 自洽性:设备自己渲染出来的块,自己必须能解回来 —— 且**字段值一字不差**。
     *
     * 这不是"自己跟自己印证"的假守卫:它锁的是 render 与 parse 的**格式约定一致**
     * (属性 vs 子元素、可选元素的缺席表示),这两侧分家时用真实报文才会暴露。
     */
    @Test fun renderThenParse_roundTripsExactly() {
        val states = listOf(
            VideoParamState(0, "2", "6", "25", "1", "4096"),
            VideoParamState(1, "5", "5", "15", "2", null),
        )
        val reparsed = VideoParamAttribute.parseItems(VideoParamAttribute.renderBlock(states))
        assertEquals(states, reparsed)
    }
}

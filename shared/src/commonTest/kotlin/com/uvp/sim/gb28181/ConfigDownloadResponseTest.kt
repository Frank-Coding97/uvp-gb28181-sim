package com.uvp.sim.gb28181

import com.uvp.sim.config.ChannelProfile
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoProfile
import com.uvp.sim.config.VideoResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfigDownloadResponseTest {

    /** 设备编码。与 cfg.device.videoChannelId 刻意不同 —— 用来锁「应答回的是请求值」这条行为。 */
    private val DEVICE_ID = "34020000001110000001"

    private val cfg = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            name = "我的设备",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password",
            // ⛔ 显式声明**单码流**：本 fixture 的大多数断言只想验证"块在不在/值对不对"，
            //    不关心行数。行数相关的守卫另有
            //    `build_videoParamAttribute_rowCountFollowsDeclaredStreamNumberList` 显式用 "0/1"。
            //    这里若吃 ChannelProfile 的默认值（"0/1"），断言会随默认值漂移而红 ——
            //    "测试跟着实现改"的坏味道就是从这种不写明前提的 fixture 开始的。
            channel = ChannelProfile(streamNumberList = "0"),
        ),
        expiresSeconds = 7200,
        keepaliveIntervalSeconds = 30,
        maxKeepaliveTimeouts = 5,
        video = VideoProfile(resolution = VideoResolution.FHD_1080P)
    )

    @Test fun parseConfigTypes_singleType() {
        val xml = "<Query><ConfigType>BasicParam</ConfigType></Query>"
        assertEquals(listOf("BasicParam"), ConfigDownloadResponse.parseConfigTypes(xml))
    }

    @Test fun parseConfigTypes_slashSeparated() {
        val xml = "<Query><ConfigType>BasicParam/VideoParamOpt/SVACEncodeConfig</ConfigType></Query>"
        assertEquals(
            listOf("BasicParam", "VideoParamOpt", "SVACEncodeConfig"),
            ConfigDownloadResponse.parseConfigTypes(xml)
        )
    }

    @Test fun parseConfigTypes_missingTag_returnsEmpty() {
        assertEquals(emptyList(), ConfigDownloadResponse.parseConfigTypes("<Query></Query>"))
    }

    @Test fun build_basicParam_readsFromConfig() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "5", requestedDeviceId = DEVICE_ID, configTypes = listOf("BasicParam"), gbVersion = GbVersion.V2022
        )
        assertTrue(xml.contains("<CmdType>ConfigDownload</CmdType>"))
        assertTrue(xml.contains("<SN>5</SN>"))
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
        assertTrue(xml.contains("<Result>OK</Result>"))
        assertTrue(xml.contains("<BasicParam>"))
        assertTrue(xml.contains("<Name>我的设备</Name>"))
        assertTrue(xml.contains("<Expiration>7200</Expiration>"))
        assertTrue(xml.contains("<HeartBeatInterval>30</HeartBeatInterval>"))
        assertTrue(xml.contains("<HeartBeatCount>5</HeartBeatCount>"))
    }

    @Test fun build_videoParamOpt_reportsAppendixGResolutionCodes() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1", requestedDeviceId = DEVICE_ID, configTypes = listOf("VideoParamOpt"), gbVersion = GbVersion.V2022
        )
        assertTrue(xml.contains("<VideoParamOpt>"))
        assertTrue(xml.contains("<DownloadSpeed>1/2/4</DownloadSpeed>"))
        // 附录 G 码值:4=D1(480P) / 5=720P / 6=1080P。报的是**档位全集**(VideoResolution.entries),
        // 不是当前生效的那一个 —— VideoParamOpt.Resolution 的语义是"支持的分辨率,多值 / 分隔"。
        assertTrue(
            xml.contains("<Resolution>4/5/6</Resolution>"),
            "actual: ${xml.lines().firstOrNull { it.contains("Resolution") }}"
        )
        // ⛔ 人读串不能进报文 —— 附录 G 只认码值或 WxH 形式。
        //    这是本轮修的缺陷:SDP 侧发过码值 5,ConfigDownload 侧却发过 "1920×1080"。
        assertFalse(xml.contains("×"), "报文里不允许出现人读串分辨率: $xml")
        assertFalse(xml.contains("1920×1080"))
        assertFalse(xml.contains("1280×720"))
    }

    @Test fun build_videoParamOpt_usesSameCodesAsSdp() {
        // 单一真源守卫:SDP 协商与配置回读必须出自 VideoResolution.gb28181Code,
        // 否则同一台设备的两条出口会再次分家(曾经 SDP 发 5、ConfigDownload 发 "720P")。
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1", requestedDeviceId = DEVICE_ID, configTypes = listOf("VideoParamOpt"), gbVersion = GbVersion.V2022
        )
        val reported = Regex("<Resolution>(.+)</Resolution>").find(xml)?.groupValues?.get(1)
        assertEquals(
            VideoResolution.entries.joinToString("/") { it.gb28181Code.toString() },
            reported,
        )
    }

    @Test fun build_multipleTypes_emitsAllBlocks() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("BasicParam", "VideoParamOpt"),
            gbVersion = GbVersion.V2022,
        )
        assertTrue(xml.contains("<BasicParam>"))
        assertTrue(xml.contains("<VideoParamOpt>"))
    }

    @Test fun build_unknownType_skippedButResultOk() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("SVACEncodeConfig"),
            gbVersion = GbVersion.V2022,
        )
        // 不识别的类型只是不输出对应块,但整体仍 Result=OK 让平台不报错
        assertTrue(xml.contains("<Result>OK</Result>"))
        assertFalse(xml.contains("<BasicParam>"))
        assertFalse(xml.contains("<VideoParamOpt>"))
        assertFalse(xml.contains("<SVACEncodeConfig>"))
    }

    @Test fun build_caseInsensitive_typeMatching() {
        // 平台可能发 "basicparam" 全小写,我们应该容忍
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1", requestedDeviceId = DEVICE_ID, configTypes = listOf("basicparam"), gbVersion = GbVersion.V2022
        )
        assertTrue(xml.contains("<BasicParam>"))
    }

    // ===== A.2.1.13 VideoParamAttribute(2022 新增的配置类型)=====

    /**
     * ⭐ 本组最重要的一条:**平台从未下发过,回读也必须有值**。
     *
     * 平台侧把「应答没带 `VideoParamAttribute` 元素」当作**「设备不支持该配置类型」最可靠的判据**
     * (规格 §十④)。所以设备若在"没人配过"时回空,就会被平台误判成"不支持" ——
     * 真实设备一上电就有编码配置,回空是模拟器**自己造出来的假阴性**。
     */
    @Test fun build_videoParamAttribute_reportsFactoryDefaultsWhenNeverConfigured() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("VideoParamAttribute"),
            gbVersion = GbVersion.V2022,
        )
        assertTrue(xml.contains("<VideoParamAttribute Num=\"1\">"), "actual: $xml")
        assertTrue(xml.contains("<StreamNumber>0</StreamNumber>"))
        // 出厂默认从 SimConfig.video 派生(cfg 里分辨率是 FHD_1080P):
        // 编码格式 2=H.264 / 分辨率 6=1080P / 帧率 25 / 码率类型 1=CBR / 码率 2000
        assertTrue(xml.contains("<VideoFormat>2</VideoFormat>"))
        assertTrue(xml.contains("<Resolution>6</Resolution>"))
        assertTrue(xml.contains("<FrameRate>25</FrameRate>"))
        assertTrue(xml.contains("<BitRateType>1</BitRateType>"))
        assertTrue(xml.contains("<VideoBitRate>2000</VideoBitRate>"))
    }

    /**
     * ⛔ §八 第 9 条验收要复现的形态:有效版本是 2016 时,2022 新增类型**整块不回**,
     * 但整体仍 `Result=OK`(标准对 2016 设备收到该类型的行为未定义)。
     *
     * 平台侧必须把这种应答落成 `type_absent`(设备不支持),而**不能**当成"设备漏答"。
     */
    @Test fun build_videoParamAttribute_blockSkippedWhenEffectiveVersionIs2016() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("VideoParamAttribute"),
            gbVersion = GbVersion.V2016,
        )
        assertTrue(xml.contains("<Result>OK</Result>"), "2016 设备仍要回 OK 让平台不重试")
        assertFalse(xml.contains("<VideoParamAttribute"), "2016 有效版本不该回 2022 新增的配置类型")
        assertFalse(xml.contains("<VideoBitRate>"))
    }

    @Test fun build_videoParamAttribute_overrideWinsOverFactoryDefault() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("VideoParamAttribute"),
            gbVersion = GbVersion.V2022,
            videoParamOverrides = mapOf(
                0 to VideoParamState(
                    streamNumber = 0, videoFormat = "5", resolution = "5",
                    frameRate = "30", bitRateType = "1", videoBitRate = "8192",
                )
            ),
        )
        assertTrue(xml.contains("<VideoFormat>5</VideoFormat>"))
        assertTrue(xml.contains("<Resolution>5</Resolution>"))
        assertTrue(xml.contains("<FrameRate>30</FrameRate>"))
        assertTrue(xml.contains("<VideoBitRate>8192</VideoBitRate>"))
        // 出厂默认不该同时出现 —— 覆盖是"替换"不是"追加"
        assertFalse(xml.contains("<Resolution>6</Resolution>"))
    }

    /**
     * ⛔ `Num` 是**属性**,不是子元素。写成 `<Num>` 平台侧 `xml:"Num,attr"` 解不出来
     * 且**不报错**,现象是"设备回了 OK 但没数据" —— 与"设备不支持"无法区分。
     */
    @Test fun build_videoParamAttribute_numIsAttributeNotChildElement() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("VideoParamAttribute"),
            gbVersion = GbVersion.V2022,
        )
        assertTrue(xml.contains("Num=\"1\""))
        assertFalse(xml.contains("<Num>"), "Num 必须是属性: $xml")
    }

    /**
     * ⛔ `VideoBitRate` 是**条件必选**(标准:固定码率时必选)。VBR 时**整个元素不能出现** ——
     * 输出空元素会让平台解出空串,与"没有这个元素"含义不同(前者会被当成"给了一个空值")。
     */
    @Test fun build_videoParamAttribute_vbrOmitsBitRateElement() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("VideoParamAttribute"),
            gbVersion = GbVersion.V2022,
            videoParamOverrides = mapOf(
                0 to VideoParamState(
                    streamNumber = 0, videoFormat = "2", resolution = "6",
                    frameRate = "25", bitRateType = "2", videoBitRate = null,
                )
            ),
        )
        assertTrue(xml.contains("<BitRateType>2</BitRateType>"))
        assertFalse(xml.contains("<VideoBitRate>"), "VBR 时该元素必须缺席: $xml")
    }

    /**
     * ⭐ 一行数必须与**目录里声明的码流清单**同源。
     *
     * 两处不一致的后果不是"少显示一个数":平台面板按目录 `StreamNumberList` 渲染 N 行,
     * 回读只有 M 行 → 看起来像「设备漏答了」。
     */
    @Test fun build_videoParamAttribute_rowCountFollowsDeclaredStreamNumberList() {
        val twoStreams = cfg.copy(
            device = cfg.device.copy(channel = ChannelProfile(streamNumberList = "0/1"))
        )
        val xml = ConfigDownloadResponse.build(
            twoStreams, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("VideoParamAttribute"),
            gbVersion = GbVersion.V2022,
        )
        assertTrue(xml.contains("<VideoParamAttribute Num=\"2\">"), "actual: $xml")
        assertTrue(xml.contains("<StreamNumber>0</StreamNumber>"))
        assertTrue(xml.contains("<StreamNumber>1</StreamNumber>"))
    }

    @Test fun build_videoParamAttribute_typeMatchingIsCaseInsensitive() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("videoparamattribute"),
            gbVersion = GbVersion.V2022,
        )
        assertTrue(xml.contains("<VideoParamAttribute"))
    }

    @Test fun build_videoParamAttribute_notEmittedWhenNotRequested() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            requestedDeviceId = DEVICE_ID,
            configTypes = listOf("BasicParam"),
            gbVersion = GbVersion.V2022,
        )
        assertFalse(xml.contains("<VideoParamAttribute"))
    }

    /** 应答构造与应答日志共用这一个判断,别在两处各写一遍大小写/版本比较。 */
    @Test fun emitsVideoParamAttribute_requiresBothRequestAnd2022() {
        val want = listOf("VideoParamAttribute")
        assertTrue(ConfigDownloadResponse.emitsVideoParamAttribute(want, GbVersion.V2022))
        assertFalse(ConfigDownloadResponse.emitsVideoParamAttribute(want, GbVersion.V2016))
        assertFalse(ConfigDownloadResponse.emitsVideoParamAttribute(listOf("BasicParam"), GbVersion.V2022))
        assertTrue(ConfigDownloadResponse.requestedVideoParamAttribute(listOf("videoparamattribute")))
    }

    /**
     * ⭐ 2026-09-18 真机实测抓到的缺陷守卫。
     *
     * 平台面板是**按通道编码**查的（请求里的 `<DeviceID>` = `channel_code`），
     * 而设备编码是另一个值。原先应答顶层恒回 `config.device.deviceId` →
     * 平台侧 `ptz/video_param.go` 的
     * `ConfigDownloadExpectation{DeviceID: operationTargetCode(operation)}` 对账失败 →
     * **整条应答被判为不属于本次操作而丢弃**，平台永远停在 `never_read`，
     * 而且**两侧日志都不报错**（设备侧照常打"已应答"，平台侧照常回 200）。
     */
    @Test fun build_echoesRequestedDeviceId_soPlatformCanMatchChannelQuery() {
        val queried = cfg.device.videoChannelId
        assertNotEquals(
            cfg.device.deviceId, queried,
            "用例前提:设备编码与通道编码必须不同,否则这条守卫证明不了任何事"
        )
        // 用 VideoParamOpt:它的块里不含 <DeviceID>,不会干扰顶层断言。
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1", requestedDeviceId = queried,
            configTypes = listOf("VideoParamOpt"), gbVersion = GbVersion.V2022,
        )
        assertTrue(
            xml.contains("<DeviceID>$queried</DeviceID>"),
            "应答顶层 DeviceID 必须回请求里的那个,否则平台对账不上。actual: $xml"
        )
        assertFalse(
            xml.contains("<DeviceID>${cfg.device.deviceId}</DeviceID>"),
            "绝不能回设备编码顶替通道编码。actual: $xml"
        )
    }
}

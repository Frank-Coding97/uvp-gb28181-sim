package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoProfile
import com.uvp.sim.config.VideoResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigDownloadResponseTest {

    private val cfg = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            name = "我的设备",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "12345"
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
        val xml = ConfigDownloadResponse.build(cfg, sn = "5", configTypes = listOf("BasicParam"))
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

    @Test fun build_videoParamOpt_readsResolution() {
        val xml = ConfigDownloadResponse.build(cfg, sn = "1", configTypes = listOf("VideoParamOpt"))
        assertTrue(xml.contains("<VideoParamOpt>"))
        assertTrue(xml.contains("<DownloadSpeed>1/2/4</DownloadSpeed>"))
        // 1920x1080 在 VideoResolution 里 label 是 "1920×1080"
        assertTrue(
            xml.contains("<Resolution>1920×1080</Resolution>"),
            "actual: ${xml.lines().firstOrNull { it.contains("Resolution") }}"
        )
    }

    @Test fun build_multipleTypes_emitsAllBlocks() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            configTypes = listOf("BasicParam", "VideoParamOpt")
        )
        assertTrue(xml.contains("<BasicParam>"))
        assertTrue(xml.contains("<VideoParamOpt>"))
    }

    @Test fun build_unknownType_skippedButResultOk() {
        val xml = ConfigDownloadResponse.build(
            cfg, sn = "1",
            configTypes = listOf("SVACEncodeConfig")
        )
        // 不识别的类型只是不输出对应块,但整体仍 Result=OK 让平台不报错
        assertTrue(xml.contains("<Result>OK</Result>"))
        assertFalse(xml.contains("<BasicParam>"))
        assertFalse(xml.contains("<VideoParamOpt>"))
        assertFalse(xml.contains("<SVACEncodeConfig>"))
    }

    @Test fun build_caseInsensitive_typeMatching() {
        // 平台可能发 "basicparam" 全小写,我们应该容忍
        val xml = ConfigDownloadResponse.build(cfg, sn = "1", configTypes = listOf("basicparam"))
        assertTrue(xml.contains("<BasicParam>"))
    }
}

package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class PresetQueryResponseTest {

    private val cfg = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "12345"
        )
    )

    @Test fun build_emptyPresetList_sumNumZero() {
        val xml = PresetQueryResponse.build(cfg, sn = "5", channelId = cfg.device.videoChannelId)
        assertTrue(xml.contains("<CmdType>PresetQuery</CmdType>"))
        assertTrue(xml.contains("<SN>5</SN>"))
        assertTrue(xml.contains("<DeviceID>34020000001320000001</DeviceID>"))
        assertTrue(xml.contains("<SumNum>0</SumNum>"))
        assertTrue(xml.contains("<PresetList Num=\"0\"/>"))
    }

    @Test fun build_blankChannelId_fallsBackToDeviceId() {
        val xml = PresetQueryResponse.build(cfg, sn = "1", channelId = "")
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
    }

    @Test fun build_xmlEncoding_isGB2312_andCrlf() {
        val xml = PresetQueryResponse.build(cfg, sn = "1", channelId = "ch")
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("\r\n"))
    }
}

package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.PtzPose
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

    // ---------- T4 真实预置位列表渲染 ----------

    @Test fun build_singlePreset_emitsItem() {
        val presets = mapOf(1 to PtzPose(0f, 0f, 1f))
        val xml = PresetQueryResponse.build(cfg, sn = "9", channelId = "ch", presets = presets)
        assertTrue(xml.contains("<SumNum>1</SumNum>"))
        assertTrue(xml.contains("<PresetList Num=\"1\">"))
        assertTrue(xml.contains("<Item><PresetID>1</PresetID><PresetName>Preset 1</PresetName></Item>"))
    }

    @Test fun build_multiplePresets_sortedAscending() {
        val presets = mapOf(
            3 to PtzPose(0f, 0f, 1f),
            1 to PtzPose(0f, 0f, 1f),
            2 to PtzPose(0f, 0f, 1f),
        )
        val xml = PresetQueryResponse.build(cfg, sn = "9", channelId = "ch", presets = presets)
        assertTrue(xml.contains("<SumNum>3</SumNum>"))
        // Item 顺序 1 → 2 → 3
        val idx1 = xml.indexOf("<PresetID>1</PresetID>")
        val idx2 = xml.indexOf("<PresetID>2</PresetID>")
        val idx3 = xml.indexOf("<PresetID>3</PresetID>")
        assertTrue(idx1 in 1..idx2 && idx2 < idx3, "应按 index 升序排列")
    }
}

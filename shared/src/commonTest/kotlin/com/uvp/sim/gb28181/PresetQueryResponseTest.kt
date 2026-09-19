package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.PtzPose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresetQueryResponseTest {

    private val cfg = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password"
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

    // ---------- 2026-09-19 G-5：名字优先用平台配的 ----------

    /**
     * ⭐ A.2.3.1.2 `PTZCmdParams/PresetName`（2022 新增）下发的名字**必须**回读出来。
     * 不回读等于"平台配的名字没保存"——平台会反复重配，操作员看着像功能坏了。
     */
    @Test fun build_usesPlatformProvidedPresetNames() {
        val presets = mapOf(1 to PtzPose(0f, 0f, 1f), 3 to PtzPose(0f, 0f, 1f))
        val names = mapOf(1 to "大门", 3 to "停车场入口")
        val xml = PresetQueryResponse.build(cfg, sn = "9", channelId = "ch", presets = presets, presetNames = names)
        assertTrue(xml.contains("<Item><PresetID>1</PresetID><PresetName>大门</PresetName></Item>"), "actual: $xml")
        assertTrue(xml.contains("<Item><PresetID>3</PresetID><PresetName>停车场入口</PresetName></Item>"))
    }

    /**
     * 只有**平台从没给过名字**的预置位才回退设备自造名。
     * ⛔ 顺序不可颠倒 —— 有平台名还发自造名，平台会以为"自己配的名字没保存"。
     */
    @Test fun build_fallsBackToLocalNameOnlyForUnnamedPresets() {
        val presets = mapOf(1 to PtzPose(0f, 0f, 1f), 2 to PtzPose(0f, 0f, 1f))
        val names = mapOf(1 to "大门")
        val xml = PresetQueryResponse.build(cfg, sn = "9", channelId = "ch", presets = presets, presetNames = names)
        assertTrue(xml.contains("<PresetName>大门</PresetName>"))
        assertTrue(xml.contains("<PresetName>Preset 2</PresetName>"), "只有 2 号回退自造名: $xml")
        assertFalse(xml.contains("<PresetName>Preset 1</PresetName>"), "1 号有平台名，不得再发自造名")
    }

    /** `PresetName` 是**必选**元素（A.2.6.10），所以回退名不是"可选装饰"。 */
    @Test fun build_everyItemAlwaysHasAPresetName() {
        val presets = mapOf(1 to PtzPose(0f, 0f, 1f), 2 to PtzPose(0f, 0f, 1f), 3 to PtzPose(0f, 0f, 1f))
        val xml = PresetQueryResponse.build(
            cfg, sn = "9", channelId = "ch",
            presets = presets, presetNames = mapOf(2 to "中门")
        )
        assertEquals(3, Regex("<PresetName>").findAll(xml).count(), "actual: $xml")
    }

    /** 平台名来自报文，含 XML 元字符时必须转义，否则整条 Response 变成畸形 XML。 */
    @Test fun build_presetNameIsXmlEscaped() {
        val presets = mapOf(1 to PtzPose(0f, 0f, 1f))
        val xml = PresetQueryResponse.build(
            cfg, sn = "9", channelId = "ch",
            presets = presets, presetNames = mapOf(1 to "a&b<c>")
        )
        assertTrue(xml.contains("<PresetName>a&amp;b&lt;c&gt;</PresetName>"), "actual: $xml")
        assertFalse(xml.contains("a&b<c>"), "未转义的名字不得出现")
    }

    /** 平台给了名字但该号预置位不存在 ⇒ 不凭空造条目（名字不是"预置位存在"的依据）。 */
    @Test fun build_platformNameForNonExistentPreset_doesNotCreateEntry() {
        val xml = PresetQueryResponse.build(
            cfg, sn = "9", channelId = "ch",
            presets = mapOf(1 to PtzPose(0f, 0f, 1f)),
            presetNames = mapOf(1 to "大门", 7 to "幽灵")
        )
        assertTrue(xml.contains("<SumNum>1</SumNum>"))
        assertEquals(1, Regex("<PresetID>").findAll(xml).count())
        assertFalse(xml.contains("幽灵"), "不存在的预置位不该被名字造出来: $xml")
    }
}

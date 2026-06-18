package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M5 batch2 §7.10 — Catalog ON/OFF 简化 NOTIFY 包结构验证。
 */
class CatalogStatusOnlyNotifyTest {

    @Test fun `OFF 包含 Event 和 Status 标签`() {
        val xml = CatalogNotifyBuilder.buildStatusOnly(
            deviceId = "34020000001110000001",
            sn = 7,
            channelId = "34020000001320000001",
            online = false
        )
        assertTrue(xml.contains("<Event>OFF</Event>"), "must contain Event OFF")
        assertTrue(xml.contains("<Status>OFF</Status>"), "must contain Status OFF")
    }

    @Test fun `ON 包含 Event 和 Status 标签`() {
        val xml = CatalogNotifyBuilder.buildStatusOnly(
            deviceId = "34020000001110000001",
            sn = 8,
            channelId = "34020000001320000001",
            online = true
        )
        assertTrue(xml.contains("<Event>ON</Event>"))
        assertTrue(xml.contains("<Status>ON</Status>"))
    }

    @Test fun `简化包不含完整字段 Manufacturer Model 等`() {
        val xml = CatalogNotifyBuilder.buildStatusOnly(
            deviceId = "34020000001110000001",
            sn = 9,
            channelId = "34020000001320000001",
            online = false
        )
        // 不应有这些字段(那是完整 Item 的)
        assertFalse(xml.contains("Manufacturer"))
        assertFalse(xml.contains("Model"))
        assertFalse(xml.contains("Owner"))
        assertFalse(xml.contains("Parental"))
        assertFalse(xml.contains("SafetyWay"))
        assertFalse(xml.contains("RegisterWay"))
        assertFalse(xml.contains("Secrecy"))
    }

    @Test fun `顶层结构 Notify CmdType Catalog SumNum 1`() {
        val xml = CatalogNotifyBuilder.buildStatusOnly(
            deviceId = "DEV001",
            sn = 10,
            channelId = "CH001",
            online = true
        )
        assertTrue(xml.contains("<Notify>"))
        assertTrue(xml.contains("<CmdType>Catalog</CmdType>"))
        assertTrue(xml.contains("<SumNum>1</SumNum>"))
        assertTrue(xml.contains("<DeviceList Num=\"1\">"))
        assertTrue(xml.contains("<SN>10</SN>"))
    }

    @Test fun `deviceId 在外层 channelId 在 Item DeviceID`() {
        val xml = CatalogNotifyBuilder.buildStatusOnly(
            deviceId = "34020000001110000001",
            sn = 11,
            channelId = "34020000001320000001",
            online = false
        )
        // 顶层 DeviceID = deviceId
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
        // Item DeviceID = channelId
        assertTrue(xml.contains("<DeviceID>34020000001320000001</DeviceID>"))
    }
}

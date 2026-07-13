package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M5 batch1 §B1 — AlarmStatusResponse builder 测试.
 *
 * GB-2022: 沿用 DeviceStatus 风格,Num+Item 嵌套, DutyStatus=ALARM|OFFDUTY
 * GB-2016: 扁平 <NotNumber>0|1</NotNumber>(国标原文用词)
 */
class AlarmStatusResponseTest {

    private fun cfg(gbVersion: GbVersion = GbVersion.V2022) = SimConfig(
        gbVersion = gbVersion,
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password"
        )
    )

    private fun snap(alarming: Boolean) = AlarmStatusSnapshot(
        alarming = alarming,
        alarmChannelId = "34020000001340000001"
    )

    // ---- B1-T1: GB-2022 不报警 ----
    @Test fun gb2022_idle_dutyOffduty() {
        val xml = AlarmStatusResponse.build(cfg(), sn = "1", snapshot = snap(alarming = false))
        assertTrue(xml.contains("<DutyStatus>OFFDUTY</DutyStatus>"))
    }

    // ---- B1-T2: GB-2022 报警中 ----
    @Test fun gb2022_alarming_dutyAlarm() {
        val xml = AlarmStatusResponse.build(cfg(), sn = "1", snapshot = snap(alarming = true))
        assertTrue(xml.contains("<DutyStatus>ALARM</DutyStatus>"))
    }

    // ---- B1-T3: GB-2022 含 Num+Item 嵌套 ----
    @Test fun gb2022_emitsNumAndItem() {
        val xml = AlarmStatusResponse.build(cfg(), sn = "1", snapshot = snap(alarming = false))
        assertTrue(xml.contains("<Num>1</Num>"))
        assertTrue(xml.contains("<Item>"))
        assertTrue(xml.contains("</Item>"))
        // Item 内含 alarm channel DeviceID
        assertTrue(xml.contains("<DeviceID>34020000001340000001</DeviceID>"))
    }

    // ---- B1-T4: GB-2016 不报警 ----
    @Test fun gb2016_idle_notNumberZero() {
        val xml = AlarmStatusResponse.build(cfg(GbVersion.V2016), sn = "1", snapshot = snap(alarming = false))
        assertTrue(xml.contains("<NotNumber>0</NotNumber>"))
    }

    // ---- B1-T5: GB-2016 报警中 ----
    @Test fun gb2016_alarming_notNumberOne() {
        val xml = AlarmStatusResponse.build(cfg(GbVersion.V2016), sn = "1", snapshot = snap(alarming = true))
        assertTrue(xml.contains("<NotNumber>1</NotNumber>"))
    }

    // ---- B1-T6: GB-2016 不应有 Item 嵌套 ----
    @Test fun gb2016_noItemNesting() {
        val xml = AlarmStatusResponse.build(cfg(GbVersion.V2016), sn = "1", snapshot = snap(alarming = true))
        assertFalse(xml.contains("<DutyStatus>"))
        assertFalse(xml.contains("<Item>"))
        assertFalse(xml.contains("<Num>"))
    }

    // ---- B1-T7: SN 透传 ----
    @Test fun snPassthrough() {
        val xml = AlarmStatusResponse.build(cfg(), sn = "42", snapshot = snap(false))
        assertTrue(xml.contains("<SN>42</SN>"))
    }

    // ---- B1-T8: DeviceID 顶层 = config.device.deviceId ----
    @Test fun topDeviceIdMatchesConfig() {
        val xml = AlarmStatusResponse.build(cfg(), sn = "1", snapshot = snap(false))
        // 顶层 Response 下第一个 DeviceID 应是设备 ID,不是 alarmChannelId
        val firstDeviceIdIdx = xml.indexOf("<DeviceID>34020000001110000001</DeviceID>")
        assertTrue(firstDeviceIdIdx > 0, "顶层 DeviceID 应为设备 ID")
    }

    // ---- B1-T9: 编码声明 + CRLF ----
    @Test fun encodingAndCrlf() {
        val xml = AlarmStatusResponse.build(cfg(), sn = "1", snapshot = snap(false))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("\r\n"))
        assertTrue(xml.contains("<CmdType>AlarmStatus</CmdType>"))
        assertTrue(xml.contains("<Result>OK</Result>"))
    }
}

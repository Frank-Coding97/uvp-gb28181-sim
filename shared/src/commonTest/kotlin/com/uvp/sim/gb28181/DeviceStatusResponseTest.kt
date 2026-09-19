package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceStatusResponseTest {

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

    private val onlineSnap = DeviceStatusSnapshot(
        online = true, deviceTime = "2026-06-13T18:00:00",
        recording = false, alarming = false
    )

    @Test fun build_online_recording_alarming_v2022() {
        val xml = DeviceStatusResponse.build(
            cfg(),
            sn = "9",
            snapshot = onlineSnap.copy(recording = true, alarming = true)
        )
        assertTrue(xml.contains("<CmdType>DeviceStatus</CmdType>"))
        assertTrue(xml.contains("<SN>9</SN>"))
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
        assertTrue(xml.contains("<Result>OK</Result>"))
        assertTrue(xml.contains("<Online>ONLINE</Online>"))
        assertTrue(xml.contains("<Status>OK</Status>"))
        assertTrue(xml.contains("<DeviceTime>2026-06-13T18:00:00</DeviceTime>"))
        assertTrue(xml.contains("<Encode>ON</Encode>"))
        assertTrue(xml.contains("<Record>ON</Record>"))
        // 2022 嵌套（带大写 Num 属性，见下方专门的两版对照用例）
        assertTrue(xml.contains("<Alarmstatus Num=\"1\">"))
        assertTrue(xml.contains("<DutyStatus>ALARM</DutyStatus>"))
        assertTrue(xml.contains("<DeviceID>34020000001340000001</DeviceID>"))
    }

    @Test fun build_offline_norecord_noalarm() {
        val xml = DeviceStatusResponse.build(
            cfg(),
            sn = "1",
            snapshot = onlineSnap.copy(online = false)
        )
        assertTrue(xml.contains("<Online>OFFLINE</Online>"))
        assertTrue(xml.contains("<Record>OFF</Record>"))
        assertTrue(xml.contains("<DutyStatus>OFFDUTY</DutyStatus>"))
    }

    /**
     * ⛔ 2016 也是**嵌套** `Alarmstatus` + `Item{DeviceID, DutyStatus}`，不是扁平数字。
     *
     * 2016 附录 A.2.6 g) 与 §9.5.3.3.2 正文（标准页 28）都这么写，正文原话
     * 「报警设备状态列表**应包括**报警设备或区域或系统编码（DeviceID）、
     * 报警设备状态（DutyStatus）」。原先发的 `<AlarmStatus>0|1</AlarmStatus>`
     * 是**非 schema 元素** —— 严格校验的对端判整条报文非法。
     *
     * 两版的差异**只有属性名大小写**：2016 `num` / 2022 `Num`。
     */
    @Test fun build_v2016_emitsNestedAlarmstatusWithLowercaseNumAttribute() {
        val xml = DeviceStatusResponse.build(
            cfg(GbVersion.V2016),
            sn = "1",
            snapshot = onlineSnap.copy(alarming = true)
        )
        assertTrue(xml.contains("<Alarmstatus num=\"1\">"), "2016 的属性名是小写 num: $xml")
        assertTrue(xml.contains("<DutyStatus>ALARM</DutyStatus>"))
        assertTrue(xml.contains("<DeviceID>34020000001340000001</DeviceID>"))
        assertFalse(xml.contains("<AlarmStatus>"), "大写 S 的扁平方块是编的，两版都 0 命中: $xml")
    }

    @Test fun build_v2016_dutyStatusOffduty_whenNotAlarming() {
        val xml = DeviceStatusResponse.build(
            cfg(GbVersion.V2016),
            sn = "1",
            snapshot = onlineSnap.copy(alarming = false)
        )
        assertTrue(xml.contains("<Alarmstatus num=\"1\">"))
        assertTrue(xml.contains("<DutyStatus>OFFDUTY</DutyStatus>"))
    }

    /**
     * ⭐ 2022 分支的属性名是**大写** `Num`。
     *
     * 本条同时钉住另一类反复踩的坑：`Num` 是 `Alarmstatus` 的**属性**，
     * 不是子元素 `<Num>1</Num>` —— 同类坑在 `RegionList` / `VideoParamAttribute`
     * 上都出现过，症状统一为"平台侧有应答无数据"。
     */
    @Test fun build_v2022_numIsAnAttribute_notAChildElement() {
        val xml = DeviceStatusResponse.build(cfg(GbVersion.V2022), sn = "1", snapshot = onlineSnap)
        assertTrue(xml.contains("<Alarmstatus Num=\"1\">"), "2022 的属性名是大写 Num: $xml")
        assertFalse(xml.contains("<Num>1</Num>"), "Num 不得是子元素: $xml")
    }

    /** 两版都要有嵌套块，只有属性名不同 —— 用同一份断言同时覆盖两版。 */
    @Test fun bothVersions_shareTheSameNestedShape() {
        for ((v, attr) in listOf(GbVersion.V2016 to "num", GbVersion.V2022 to "Num")) {
            val xml = DeviceStatusResponse.build(cfg(v), sn = "1", snapshot = onlineSnap.copy(alarming = true))
            assertTrue(xml.contains("<Alarmstatus $attr=\"1\">"), "$v 应有 <Alarmstatus $attr=\"1\">: $xml")
            assertTrue(xml.contains("<Item>"), "$v 应有 Item: $xml")
            assertTrue(xml.contains("<DutyStatus>ALARM</DutyStatus>"), "$v: $xml")
            assertTrue(xml.contains("</Alarmstatus>"), "$v: $xml")
        }
    }

    @Test fun build_xmlEncoding_isGB2312_andCrlf() {
        val xml = DeviceStatusResponse.build(cfg(), sn = "1", snapshot = onlineSnap)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("\r\n"))
    }
}

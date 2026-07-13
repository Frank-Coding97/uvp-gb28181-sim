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
        // 2022 嵌套
        assertTrue(xml.contains("<Alarmstatus>"))
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

    @Test fun build_v2016_emits_flat_alarmStatus_number() {
        val xml = DeviceStatusResponse.build(
            cfg(GbVersion.V2016),
            sn = "1",
            snapshot = onlineSnap.copy(alarming = true)
        )
        // GB-2016 扁平 1/0
        assertTrue(xml.contains("<AlarmStatus>1</AlarmStatus>"))
        // 不应含 GB-2022 嵌套 tag
        assertFalse(xml.contains("<Alarmstatus>"))
        assertFalse(xml.contains("<DutyStatus>"))
    }

    @Test fun build_v2016_alarmZero_whenNotAlarming() {
        val xml = DeviceStatusResponse.build(
            cfg(GbVersion.V2016),
            sn = "1",
            snapshot = onlineSnap.copy(alarming = false)
        )
        assertTrue(xml.contains("<AlarmStatus>0</AlarmStatus>"))
    }

    @Test fun build_xmlEncoding_isGB2312_andCrlf() {
        val xml = DeviceStatusResponse.build(cfg(), sn = "1", snapshot = onlineSnap)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("\r\n"))
    }
}

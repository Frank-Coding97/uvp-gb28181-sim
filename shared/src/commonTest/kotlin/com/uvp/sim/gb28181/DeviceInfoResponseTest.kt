package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceInfoResponseTest {

    private fun cfg(
        gbVersion: GbVersion = GbVersion.V2022,
        manufacturer: String = "UVP",
        model: String = "GB28181-Sim",
        firmware: String = "0.1.0",
        hardware: String = "Mobile"
    ) = SimConfig(
        gbVersion = gbVersion,
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "12345",
            manufacturer = manufacturer,
            model = model,
            firmware = firmware,
            hardwareVersion = hardware
        )
    )

    @Test fun build_v2022_containsAllRequiredTags() {
        val xml = DeviceInfoResponse.build(cfg(), sn = "42")
        assertTrue(xml.contains("<CmdType>DeviceInfo</CmdType>"))
        assertTrue(xml.contains("<SN>42</SN>"))
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
        assertTrue(xml.contains("<DeviceName>UVP-Sim</DeviceName>"))
        assertTrue(xml.contains("<Manufacturer>UVP</Manufacturer>"))
        assertTrue(xml.contains("<Model>GB28181-Sim</Model>"))
        assertTrue(xml.contains("<Firmware>0.1.0</Firmware>"))
        assertTrue(xml.contains("<Result>OK</Result>"))
        // Channel 数永远 1(M2 单通道)
        assertTrue(xml.contains("<Channel>1</Channel>"))
    }

    @Test fun build_v2022_includesHardwareVersion() {
        // GB-2022 §9.3.2 多了 HardwareVersion 字段(GB-2016 没有)
        val xml = DeviceInfoResponse.build(cfg(gbVersion = GbVersion.V2022), sn = "1")
        assertTrue(
            xml.contains("<HardwareVersion>Mobile</HardwareVersion>"),
            "v2022 应输出 HardwareVersion"
        )
    }

    @Test fun build_v2016_omitsHardwareVersion() {
        val xml = DeviceInfoResponse.build(cfg(gbVersion = GbVersion.V2016), sn = "1")
        assertFalse(
            xml.contains("HardwareVersion"),
            "v2016 不应输出 HardwareVersion"
        )
    }

    @Test fun build_xmlEncoding_isGB2312() {
        val xml = DeviceInfoResponse.build(cfg(), sn = "1")
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
    }

    @Test fun build_lineSeparator_isCrlf() {
        val xml = DeviceInfoResponse.build(cfg(), sn = "1")
        // GB28181 SIP body 习惯 CRLF
        assertTrue(xml.contains("\r\n"))
        // 不应有裸 LF (除了 \r\n 序列里的 \n)
        val standaloneLf = Regex("(?<!\r)\n").findAll(xml).count()
        assertEquals(0, standaloneLf, "should not contain standalone LF")
    }

    @Test fun build_chineseChars_kept() {
        // 中文厂商名(国标设备很常见)
        val xml = DeviceInfoResponse.build(cfg(manufacturer = "海康威视"), sn = "1")
        assertTrue(xml.contains("<Manufacturer>海康威视</Manufacturer>"))
    }
}

package com.uvp.sim.gb28181

import com.uvp.sim.config.ChannelProfile
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.DirectionType
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.PositionType
import com.uvp.sim.config.PtzType
import com.uvp.sim.config.RoomType
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.SupplyLightType
import com.uvp.sim.config.UseType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogResponseTest {

    private fun cfg(
        gbVersion: GbVersion = GbVersion.V2022,
        channel: ChannelProfile = ChannelProfile()
    ) = SimConfig(
        gbVersion = gbVersion,
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "12345",
            channel = channel
        )
    )

    @Test fun build_v2022_emitsAllNewFields() {
        val xml = CatalogResponse.build(cfg(), sn = "5")
        // 现有 GB-2016 基础字段
        assertTrue(xml.contains("<CmdType>Catalog</CmdType>"))
        assertTrue(xml.contains("<SumNum>1</SumNum>"))
        assertTrue(xml.contains("<DeviceList Num=\"1\">"))
        assertTrue(xml.contains("<DeviceID>34020000001320000001</DeviceID>"))
        assertTrue(xml.contains("<Status>ON</Status>"))
        // GB-2022 新增 10 个字段
        assertTrue(xml.contains("<IPAddress>"))
        assertTrue(xml.contains("<Port>"))
        assertTrue(xml.contains("<PTZType>"))
        assertTrue(xml.contains("<PositionType>"))
        assertTrue(xml.contains("<RoomType>"))
        assertTrue(xml.contains("<UseType>"))
        assertTrue(xml.contains("<SupplyLightType>"))
        assertTrue(xml.contains("<DirectionType>"))
        assertTrue(xml.contains("<Resolution>"))
        assertTrue(xml.contains("<BusinessGroupID>"))
    }

    @Test fun build_v2022_usesEnumGbCodes() {
        val ch = ChannelProfile(
            ptzType = PtzType.Dome,
            positionType = PositionType.City,
            roomType = RoomType.Indoor,
            useType = UseType.Traffic,
            supplyLightType = SupplyLightType.Infrared,
            directionType = DirectionType.Southeast,
            ipAddress = "192.168.1.100",
            port = 5061,
            resolution = "1920*1080",
            businessGroupId = "BG-001"
        )
        val xml = CatalogResponse.build(cfg(channel = ch), sn = "1")
        assertTrue(xml.contains("<IPAddress>192.168.1.100</IPAddress>"))
        assertTrue(xml.contains("<Port>5061</Port>"))
        assertTrue(xml.contains("<PTZType>1</PTZType>"))           // Dome=1
        assertTrue(xml.contains("<PositionType>2</PositionType>")) // City=2
        assertTrue(xml.contains("<RoomType>1</RoomType>"))         // Indoor=1
        assertTrue(xml.contains("<UseType>2</UseType>"))           // Traffic=2
        assertTrue(xml.contains("<SupplyLightType>2</SupplyLightType>")) // Infrared=2
        assertTrue(xml.contains("<DirectionType>5</DirectionType>"))     // Southeast=5
        assertTrue(xml.contains("<Resolution>1920*1080</Resolution>"))
        assertTrue(xml.contains("<BusinessGroupID>BG-001</BusinessGroupID>"))
    }

    @Test fun build_v2016_omitsAllNewFields() {
        val xml = CatalogResponse.build(cfg(gbVersion = GbVersion.V2016), sn = "1")
        // 现有 GB-2016 基础字段照旧
        assertTrue(xml.contains("<CmdType>Catalog</CmdType>"))
        assertTrue(xml.contains("<DeviceID>34020000001320000001</DeviceID>"))
        // GB-2022 新字段一个都不应出现
        assertFalse(xml.contains("<IPAddress>"))
        assertFalse(xml.contains("<Port>"))
        assertFalse(xml.contains("<PTZType>"))
        assertFalse(xml.contains("<PositionType>"))
        assertFalse(xml.contains("<RoomType>"))
        assertFalse(xml.contains("<UseType>"))
        assertFalse(xml.contains("<SupplyLightType>"))
        assertFalse(xml.contains("<DirectionType>"))
        assertFalse(xml.contains("<Resolution>"))
        assertFalse(xml.contains("<BusinessGroupID>"))
    }

    @Test fun build_manufacturer_readsFromConfig() {
        // 让 manufacturer 不再硬编码 "UVP",而是从 device.manufacturer 取
        val custom = cfg().run {
            copy(device = device.copy(manufacturer = "海康威视", model = "DS-IPC-Mock"))
        }
        val xml = CatalogResponse.build(custom, sn = "1")
        assertTrue(xml.contains("<Manufacturer>海康威视</Manufacturer>"))
        assertTrue(xml.contains("<Model>DS-IPC-Mock</Model>"))
    }

    @Test fun build_xmlEncoding_isGB2312_andCrlf() {
        val xml = CatalogResponse.build(cfg(), sn = "1")
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("\r\n"))
    }
}

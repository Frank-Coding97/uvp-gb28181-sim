package com.uvp.sim.gb28181

import com.uvp.sim.config.GeoPoint
import kotlin.test.Test
import kotlin.test.assertTrue

class MobilePositionResponseTest {

    @Test fun build_containsAllRequiredTags() {
        val xml = MobilePositionResponse.build(
            deviceId = "34020000001110000001",
            sn = "11",
            point = GeoPoint(longitude = 116.404123, latitude = 39.915456),
            speed = 12.5,
            direction = 90.0,
            altitude = 50.0,
            timestamp = "2026-06-13T18:00:00"
        )
        assertTrue(xml.contains("<CmdType>MobilePosition</CmdType>"))
        assertTrue(xml.contains("<SN>11</SN>"))
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
        assertTrue(xml.contains("<Result>OK</Result>"))
        assertTrue(xml.contains("<Time>2026-06-13T18:00:00</Time>"))
        assertTrue(xml.contains("<Longitude>116.404123</Longitude>"))
        assertTrue(xml.contains("<Latitude>39.915456</Latitude>"))
        assertTrue(xml.contains("<Speed>12.5</Speed>"))
        assertTrue(xml.contains("<Direction>90.0</Direction>"))
        assertTrue(xml.contains("<Altitude>50.0</Altitude>"))
    }

    @Test fun build_isResponseNotNotifyWrapper() {
        // Notify 用于周期推送(8.3),Response 用于单次查询(3.7),不能搞混
        val xml = MobilePositionResponse.build(
            deviceId = "d", sn = "1",
            point = GeoPoint(116.0, 39.0),
            speed = 0.0, direction = 0.0, altitude = 0.0,
            timestamp = "2026-06-13T18:00:00"
        )
        assertTrue(xml.contains("<Response>"))
        assertTrue(xml.contains("</Response>"))
        // 不应包含 Notify wrapper
        assertTrue(!xml.contains("<Notify>"), "must use <Response> not <Notify>")
    }

    @Test fun build_decimals_rounded() {
        val xml = MobilePositionResponse.build(
            deviceId = "d", sn = "1",
            point = GeoPoint(longitude = 116.4041239, latitude = 39.9154566),
            speed = 0.05,
            direction = 359.95,
            altitude = 99.99,
            timestamp = "2026-06-13T18:00:00"
        )
        // 经纬度 6 位小数四舍五入
        assertTrue(xml.contains("<Longitude>116.404124</Longitude>"))
        assertTrue(xml.contains("<Latitude>39.915457</Latitude>"))
        // 速度/方向/高度 1 位小数
        assertTrue(xml.contains("<Speed>0.1</Speed>"))
        assertTrue(xml.contains("<Direction>360.0</Direction>"))
        assertTrue(xml.contains("<Altitude>100.0</Altitude>"))
    }

    @Test fun build_negativeCoordinates() {
        val xml = MobilePositionResponse.build(
            deviceId = "d", sn = "1",
            point = GeoPoint(longitude = -73.989, latitude = -33.987),
            speed = 0.0, direction = 0.0, altitude = 0.0,
            timestamp = "2026-06-13T18:00:00"
        )
        assertTrue(xml.contains("<Longitude>-73.989000</Longitude>"))
        assertTrue(xml.contains("<Latitude>-33.987000</Latitude>"))
    }

    @Test fun build_xmlEncoding_isGB2312_andCrlf() {
        val xml = MobilePositionResponse.build(
            deviceId = "d", sn = "1",
            point = GeoPoint(0.0, 0.0),
            speed = 0.0, direction = 0.0, altitude = 0.0,
            timestamp = "t"
        )
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("\r\n"))
    }
}

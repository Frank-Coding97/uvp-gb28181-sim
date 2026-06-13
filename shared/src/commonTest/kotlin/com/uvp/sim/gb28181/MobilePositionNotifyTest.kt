package com.uvp.sim.gb28181

import com.uvp.sim.config.GeoPoint
import kotlin.test.Test
import kotlin.test.assertTrue

class MobilePositionNotifyTest {

    @Test
    fun buildContainsCmdType() {
        val xml = MobilePositionNotify.build(
            deviceId = "34020000001110000001",
            sn = 1,
            point = GeoPoint(116.404000, 39.915000),
            speed = 5.0,
            direction = 90.0
        )
        assertTrue(xml.contains("<CmdType>MobilePosition</CmdType>"))
    }

    @Test
    fun buildContainsDeviceId() {
        val xml = MobilePositionNotify.build(
            deviceId = "34020000001110000001",
            sn = 2,
            point = GeoPoint(116.404000, 39.915000),
            speed = 0.0,
            direction = 0.0
        )
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
    }

    @Test
    fun buildFormatsCoordinatesTo6Decimals() {
        val xml = MobilePositionNotify.build(
            deviceId = "dev1",
            sn = 3,
            point = GeoPoint(116.4, 39.9),
            speed = 10.0,
            direction = 180.0
        )
        assertTrue(xml.contains("<Longitude>116.400000</Longitude>"))
        assertTrue(xml.contains("<Latitude>39.900000</Latitude>"))
    }

    @Test
    fun buildContainsTimeTag() {
        val xml = MobilePositionNotify.build(
            deviceId = "dev1",
            sn = 4,
            point = GeoPoint(116.404, 39.915),
            speed = 0.0,
            direction = 0.0,
            timestamp = "2026-06-12T22:00:00"
        )
        assertTrue(xml.contains("<Time>2026-06-12T22:00:00</Time>"))
    }

    @Test
    fun buildContainsSpeedAndDirection() {
        val xml = MobilePositionNotify.build(
            deviceId = "dev1",
            sn = 5,
            point = GeoPoint(116.404, 39.915),
            speed = 25.3,
            direction = 270.5
        )
        assertTrue(xml.contains("<Speed>25.3</Speed>"))
        assertTrue(xml.contains("<Direction>270.5</Direction>"))
    }
}

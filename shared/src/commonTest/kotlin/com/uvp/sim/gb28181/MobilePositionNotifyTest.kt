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

    /**
     * KMP-purify regression: `"%.6f".format(...)` and `"%.1f".format(...)` were
     * replaced with a hand-rolled half-up formatter (commonMain has no
     * String.format on Kotlin/Native). Lock in expected byte-equivalent output
     * for a representative set of inputs so any future drift fails loudly.
     *
     * Cases cover: integer values, plain decimals, half-up rounding boundaries,
     * trailing-zero padding, negative values (altitude can be below sea level),
     * and exact-fraction edge cases.
     */
    @Test
    fun buildFormatsNumbersAsExpectedFixtures() {
        val xml = MobilePositionNotify.build(
            deviceId = "dev1",
            sn = 99,
            point = GeoPoint(longitude = 116.4045678, latitude = -39.9154321),
            speed = 12.34,           // %.1f → 12.3 (half-down at .34)
            direction = 0.05,        // %.1f → 0.1 (half-up at .05)
            altitude = -7.25,        // %.1f → -7.3 (half-up away from zero on neg)
            timestamp = "2026-06-27T10:00:00"
        )
        // Longitude/latitude formatted to 6 decimals, half-up at .5
        assertTrue(
            xml.contains("<Longitude>116.404568</Longitude>"),
            "longitude format mismatch: $xml"
        )
        assertTrue(
            xml.contains("<Latitude>-39.915432</Latitude>"),
            "latitude format mismatch: $xml"
        )
        assertTrue(xml.contains("<Speed>12.3</Speed>"), "speed format mismatch: $xml")
        assertTrue(xml.contains("<Direction>0.1</Direction>"), "direction format mismatch: $xml")
        assertTrue(xml.contains("<Altitude>-7.3</Altitude>"), "altitude format mismatch: $xml")
    }

    @Test
    fun buildFormatsTrailingZerosWithFixedDecimals() {
        // Integer-valued inputs must still emit the configured decimal places
        // (e.g. 1.0 → "1.0" for %.1f, 116.0 → "116.000000" for %.6f).
        val xml = MobilePositionNotify.build(
            deviceId = "dev1",
            sn = 100,
            point = GeoPoint(longitude = 116.0, latitude = -1.0),
            speed = 0.0,
            direction = 360.0,
            altitude = 0.0,
            timestamp = "2026-06-27T10:00:00"
        )
        assertTrue(xml.contains("<Longitude>116.000000</Longitude>"))
        assertTrue(xml.contains("<Latitude>-1.000000</Latitude>"))
        assertTrue(xml.contains("<Speed>0.0</Speed>"))
        assertTrue(xml.contains("<Direction>360.0</Direction>"))
        assertTrue(xml.contains("<Altitude>0.0</Altitude>"))
    }
}

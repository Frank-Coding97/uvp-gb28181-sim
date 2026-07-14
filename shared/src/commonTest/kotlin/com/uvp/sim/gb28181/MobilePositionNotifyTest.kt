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
        // 2026-06-12T22:00:00 东八区 → epoch 1_781_272_800_000 ms
        val xml = MobilePositionNotify.build(
            deviceId = "dev1",
            sn = 4,
            point = GeoPoint(116.404, 39.915),
            speed = 0.0,
            direction = 0.0,
            fixTimeMs = 1_781_272_800_000L,
        )
        assertTrue(xml.contains("<Time>2026-06-12T22:00:00</Time>"), "time format mismatch: $xml")
    }

    /** plan §6.1 Codex R1 P2 — fixTimeMs 毫秒级输入必须秒截断(不四舍五入)。 */
    @Test
    fun buildTruncatesFixTimeMsToSecondsNotRoundHalfUp() {
        // 2026-06-12T22:00:00.999 东八区 → 秒截断到 :00 而不是 half-up 到 :01
        val xml = MobilePositionNotify.build(
            deviceId = "dev1",
            sn = 4,
            point = GeoPoint(116.404, 39.915),
            speed = 0.0,
            direction = 0.0,
            fixTimeMs = 1_781_272_800_999L, // 22:00:00.999
        )
        assertTrue(xml.contains("<Time>2026-06-12T22:00:00</Time>"), "expected :00 truncation, got: $xml")
    }

    /** plan §6.2 Codex R1 P1 — speed 单位换算 m/s → km/h 由 builder 承担。 */
    @Test
    fun buildConvertsSpeedFromMetersPerSecondToKmh() {
        val xml = MobilePositionNotify.build(
            deviceId = "dev1",
            sn = 42,
            point = GeoPoint(116.404, 39.915),
            speed = 10.0,      // m/s
            direction = 90.0,
        )
        // 10 m/s × 3.6 = 36.0 km/h
        assertTrue(xml.contains("<Speed>36.0</Speed>"), "speed conversion mismatch: $xml")
    }

    @Test
    fun buildContainsSpeedAndDirection() {
        val xml = MobilePositionNotify.build(
            deviceId = "dev1",
            sn = 5,
            point = GeoPoint(116.404, 39.915),
            speed = 25.3,          // m/s
            direction = 270.5
        )
        // speed 25.3 m/s × 3.6 = 91.08 km/h → half-up 到 91.1(plan §6.2)
        assertTrue(xml.contains("<Speed>91.1</Speed>"))
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
            speed = 12.34,           // m/s → 12.34 × 3.6 = 44.424 km/h → 44.4
            direction = 0.05,        // %.1f → 0.1 (half-up at .05)
            altitude = -7.25,        // %.1f → -7.3 (half-up away from zero on neg)
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
        // 12.34 m/s × 3.6 = 44.424 km/h → half-up 44.4(plan §6.2)
        assertTrue(xml.contains("<Speed>44.4</Speed>"), "speed format mismatch: $xml")
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
            speed = 0.0,       // m/s → 0.0 km/h
            direction = 360.0,
            altitude = 0.0,
        )
        assertTrue(xml.contains("<Longitude>116.000000</Longitude>"))
        assertTrue(xml.contains("<Latitude>-1.000000</Latitude>"))
        assertTrue(xml.contains("<Speed>0.0</Speed>"))
        assertTrue(xml.contains("<Direction>360.0</Direction>"))
        assertTrue(xml.contains("<Altitude>0.0</Altitude>"))
    }
}

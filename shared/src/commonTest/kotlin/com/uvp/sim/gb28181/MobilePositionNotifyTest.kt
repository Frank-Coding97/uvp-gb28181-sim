package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobilePositionNotifyTest {

    @Test
    fun buildContainsCmdType() {
        val xml = MobilePositionNotify.build(
            sourceChannelId = "34020000001110000001",
            gbVersion = GbVersion.V2016,
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
            sourceChannelId = "34020000001110000001",
            gbVersion = GbVersion.V2016,
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
            sourceChannelId = "dev1",
            gbVersion = GbVersion.V2016,
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
            sourceChannelId = "dev1",
            gbVersion = GbVersion.V2016,
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
            sourceChannelId = "dev1",
            gbVersion = GbVersion.V2016,
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
            sourceChannelId = "dev1",
            gbVersion = GbVersion.V2016,
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
            sourceChannelId = "dev1",
            gbVersion = GbVersion.V2016,
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
            sourceChannelId = "dev1",
            gbVersion = GbVersion.V2016,
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
            sourceChannelId = "dev1",
            gbVersion = GbVersion.V2016,
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

    // ==========================================================================
    // 以下为 F-10 双版本并存(2026-09-17)。
    // 上面 9 例全部显式标 `GbVersion.V2016` —— 它们不只是"老测试",还是
    // **「改 2022 没打破 2016」的回归证据**。下面的 golden 再把它锁到逐字节。
    // ==========================================================================

    /**
     * ⛔ F-10 硬约束回归:2016 扁平形态必须**逐字节不变**。
     *
     * 用整包 golden 字符串锁死 —— 字段增删、换序、缩进、换行符(CRLF)任一变化都会红。
     * `rootDeviceId` 刻意传了一个**不等于** `sourceChannelId` 的值:2016 形态必须忽略它,
     * 根 `<DeviceID>` 只能是位置来源通道(平台按它定位通道 / 写 SourceCode)。
     */
    @Test
    fun build2016GoldenBodyIsByteIdentical() {
        val xml = MobilePositionNotify.build(
            sourceChannelId = "CH1",
            rootDeviceId = "DEV1",
            gbVersion = GbVersion.V2016,
            sn = 7,
            point = GeoPoint(116.404, 39.915),
            speed = 10.0,
            direction = 90.0,
            altitude = 5.0,
            fixTimeMs = 1_781_272_800_000L,
        )
        val expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
            "<Notify>\r\n" +
            "<CmdType>MobilePosition</CmdType>\r\n" +
            "<SN>7</SN>\r\n" +
            "<DeviceID>CH1</DeviceID>\r\n" +
            "<Time>2026-06-12T22:00:00</Time>\r\n" +
            "<Longitude>116.404000</Longitude>\r\n" +
            "<Latitude>39.915000</Latitude>\r\n" +
            "<Speed>36.0</Speed>\r\n" +
            "<Direction>90.0</Direction>\r\n" +
            "<Altitude>5.0</Altitude>\r\n" +
            "</Notify>\r\n"
        assertEquals(expected, xml, "2016 扁平形态被打破了(F-10 硬约束)")
    }

    /**
     * 2022 列表形态整包 golden —— 元素序直接照 A.2.5.6 与 A.2.1.14 抄,
     * 顺序写错会让"照标准逐行核对"这件事失效,所以用 golden 锁住。
     */
    @Test
    fun build2022GoldenBodyFollowsAppendixAOrder() {
        val xml = MobilePositionNotify.build(
            sourceChannelId = "CH1",
            rootDeviceId = "DEV1",
            gbVersion = GbVersion.V2022,
            sn = 7,
            point = GeoPoint(116.404, 39.915),
            speed = 10.0,
            direction = 90.0,
            altitude = 5.0,
            fixTimeMs = 1_781_272_800_000L, // 采集 2026-06-12T22:00:00
            notifyTimeMs = 1_781_276_400_000L, // 上报 2026-06-12T23:00:00(+1h)
        )
        val expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
            "<Notify>\r\n" +
            "<CmdType>MobilePosition</CmdType>\r\n" +
            "<SN>7</SN>\r\n" +
            "<DeviceID>DEV1</DeviceID>\r\n" +
            "<Time>2026-06-12T23:00:00</Time>\r\n" +
            "<SumNum>1</SumNum>\r\n" +
            "<DeviceList Num=\"1\">\r\n" +
            "<Item>\r\n" +
            "<DeviceID>CH1</DeviceID>\r\n" +
            "<CaptureTime>2026-06-12T22:00:00</CaptureTime>\r\n" +
            "<Longitude>116.404000</Longitude>\r\n" +
            "<Latitude>39.915000</Latitude>\r\n" +
            "<Speed>36.0</Speed>\r\n" +
            "<Direction>90.0</Direction>\r\n" +
            "<Altitude>5.0</Altitude>\r\n" +
            "</Item>\r\n" +
            "</DeviceList>\r\n" +
            "</Notify>\r\n"
        assertEquals(expected, xml)
    }

    /**
     * 两形态的**语义差异**必须真的体现出来,不是只换个壳:
     *   · 2022 根 `<Time>` 是**上报通知时间**,采集时间下沉到 `Item/CaptureTime`;
     *   · 2016 根 `<Time>` 就是**采集时间**。
     * 只断言"含 CaptureTime"是抓不住"把 fixTime 填进根 Time"这种错法的。
     */
    @Test
    fun build2022PutsNotifyTimeAtRootAndCaptureTimeInsideItem() {
        val xml = MobilePositionNotify.build(
            sourceChannelId = "CH1",
            gbVersion = GbVersion.V2022,
            sn = 1,
            point = GeoPoint(116.4, 39.9),
            speed = 0.0,
            direction = 0.0,
            fixTimeMs = 1_781_272_800_000L, // 22:00:00
            notifyTimeMs = 1_781_276_400_000L, // 23:00:00
        )
        assertTrue(xml.contains("<Time>2026-06-12T23:00:00</Time>"), "根 Time 应是上报通知时间: $xml")
        assertTrue(
            xml.contains("<CaptureTime>2026-06-12T22:00:00</CaptureTime>"),
            "Item/CaptureTime 应是采集时间: $xml",
        )
    }

    /** 2022 的根不能再出现扁平坐标 —— 否则两形态混在一包里,解析侧无法判定形态。 */
    @Test
    fun build2022DoesNotEmitFlatCoordinatesAtRoot() {
        val xml = MobilePositionNotify.build(
            sourceChannelId = "CH1",
            gbVersion = GbVersion.V2022,
            sn = 1,
            point = GeoPoint(116.4, 39.9),
            speed = 0.0,
            direction = 0.0,
            fixTimeMs = 1_781_272_800_000L,
            notifyTimeMs = 1_781_276_400_000L,
        )
        assertFalse(
            xml.contains("<Time>2026-06-12T23:00:00</Time>\r\n<Longitude>"),
            "根 Time 之后应紧跟 SumNum(根上不得有扁平坐标): $xml",
        )
        assertTrue(xml.contains("<Time>2026-06-12T23:00:00</Time>\r\n<SumNum>1</SumNum>"))
    }

    /**
     * 根 `<DeviceID>` 与 `Item/DeviceID` 的**分工**:
     *   2022 → 根 = 目标设备(rootDeviceId),通道下沉到 Item(A.2.5.6 / A.2.1.14);
     *   2016 → 根 = 位置来源通道,rootDeviceId **被忽略**。
     * 传错方向会让合规平台认不出订阅目标而丢弃整条 NOTIFY。
     */
    @Test
    fun buildRootAndItemDeviceIdAreSplitIn2022AndMergedIn2016() {
        val v2022 = MobilePositionNotify.build(
            sourceChannelId = "CH1",
            rootDeviceId = "DEV1",
            gbVersion = GbVersion.V2022,
            sn = 1,
            point = GeoPoint(116.4, 39.9),
            speed = 0.0,
            direction = 0.0,
            fixTimeMs = 1_781_272_800_000L,
        )
        assertTrue(v2022.contains("<DeviceID>DEV1</DeviceID>"), "2022 根应是目标设备: $v2022")
        assertTrue(v2022.contains("<Item>\r\n<DeviceID>CH1</DeviceID>"), "2022 通道应在 Item 内: $v2022")

        val v2016 = MobilePositionNotify.build(
            sourceChannelId = "CH1",
            rootDeviceId = "DEV1",
            gbVersion = GbVersion.V2016,
            sn = 1,
            point = GeoPoint(116.4, 39.9),
            speed = 0.0,
            direction = 0.0,
            fixTimeMs = 1_781_272_800_000L,
        )
        assertTrue(v2016.contains("<DeviceID>CH1</DeviceID>"), "2016 根应是来源通道: $v2016")
        assertFalse(v2016.contains("DEV1"), "2016 形态必须忽略 rootDeviceId: $v2016")
    }

    /**
     * 同一个 fix 在两版报文里必须给出**相同的坐标/速度/方向串**。
     * 否则同一个点会因版本不同而出现精度或单位漂移,跨版本对账时被误判成"设备位置跳变"。
     */
    @Test
    fun buildBothFormsAgreeOnCoordinateSpeedDirectionStrings() {
        fun build(version: GbVersion) = MobilePositionNotify.build(
            sourceChannelId = "CH1",
            gbVersion = version,
            sn = 1,
            point = GeoPoint(116.4045678, -39.9154321),
            speed = 12.34, // m/s → 44.4 km/h
            direction = 0.05, // → 0.1
            altitude = -7.25, // → -7.3
            fixTimeMs = 1_781_272_800_000L,
        )
        val fragments = listOf(
            "<Longitude>116.404568</Longitude>",
            "<Latitude>-39.915432</Latitude>",
            "<Speed>44.4</Speed>",
            "<Direction>0.1</Direction>",
            "<Altitude>-7.3</Altitude>",
        )
        val v2016 = build(GbVersion.V2016)
        val v2022 = build(GbVersion.V2022)
        for (fragment in fragments) {
            assertTrue(v2016.contains(fragment), "2016 缺 $fragment: $v2016")
            assertTrue(v2022.contains(fragment), "2022 缺 $fragment: $v2022")
        }
        // 同一个采集时间:2016 落根 `<Time>`,2022 落 `Item/CaptureTime` —— 容器不同,值必须一致。
        assertTrue(v2016.contains("<Time>2026-06-12T22:00:00</Time>"), "2016 采集时间: $v2016")
        assertTrue(v2022.contains("<CaptureTime>2026-06-12T22:00:00</CaptureTime>"), "2022 采集时间: $v2022")
    }

    /** 2022 可选 `Height`(地面高度)本仓无数据源 → 不该凭空输出一个 0.0。 */
    @Test
    fun build2022OmitsHeightWhenNoDataSource() {
        val xml = MobilePositionNotify.build(
            sourceChannelId = "CH1",
            gbVersion = GbVersion.V2022,
            sn = 1,
            point = GeoPoint(116.4, 39.9),
            speed = 0.0,
            direction = 0.0,
            altitude = 12.5,
            fixTimeMs = 1_781_272_800_000L,
        )
        assertTrue(xml.contains("<Altitude>12.5</Altitude>"))
        assertFalse(xml.contains("<Height>"), "无可选值就不该发可选字段: $xml")
    }
}

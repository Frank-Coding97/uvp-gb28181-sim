package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GB/T 28181-2022 A.2.5.8 设备实时视音频回传通知。
 *
 * ⚠️ **口径说明（标准留白，不是缺口）**：`VideoUploadNotify` 在 2022 正文里**没有对应小节**，
 * 「回传」一词全书只出现在附录 A.2.5.8 那两行 —— 典型的「附录定了报文、正文没给流程」。
 * 所以模拟器当前**只提供报文构造能力，未接入任何触发点**：
 * ⛔ 别为了"用上它"随便挂个时机，那会伪造一条标准里没有流程的行为。
 */
class VideoUploadNotifyTest {

    private val deviceId = "34020000001320000001"

    @Test
    fun cmdType_is_VideoUploadNotify() {
        assertEquals("VideoUploadNotify", VideoUploadNotify.CMD_TYPE)
        val xml = VideoUploadNotify.build(12, deviceId, "2026-09-19T08:40:00")
        assertTrue(xml.contains("<CmdType>VideoUploadNotify</CmdType>"))
        assertTrue(xml.contains("<Notify>"))
        assertTrue(xml.contains("</Notify>"))
    }

    @Test
    fun timeIsMandatory() {
        val xml = VideoUploadNotify.build(12, deviceId, "2026-09-19T08:40:00")
        assertTrue(xml.contains("<Time>2026-09-19T08:40:00</Time>"))
    }

    /**
     * 经纬度各自 `minOccurs=0`：**有就发、没有就不发**。
     * ⛔ 补一个 `0.0` 会把"设备不知道自己在哪"伪造成"设备在几内亚湾"（0,0）。
     */
    @Test
    fun coordinates_areOmittedWhenUnknown_notZeroFilled() {
        val xml = VideoUploadNotify.build(12, deviceId, "2026-09-19T08:40:00")
        assertFalse(xml.contains("<Longitude>"), "不知道位置就不发: $xml")
        assertFalse(xml.contains("<Latitude>"), "不知道位置就不发: $xml")
    }

    @Test
    fun coordinates_useSixDecimals() {
        val xml = VideoUploadNotify.build(
            12, deviceId, "2026-09-19T08:40:00",
            longitude = 116.404, latitude = 39.915
        )
        assertTrue(xml.contains("<Longitude>116.404000</Longitude>"))
        assertTrue(xml.contains("<Latitude>39.915000</Latitude>"))
    }

    /** 坐标格式与 MobilePositionNotify 同源（经纬度的单一格式化真源，跨报文可对账）。 */
    @Test
    fun coordinates_shareFormattingWithMobilePositionNotify() {
        assertEquals(
            MobilePositionNotify.formatDouble(116.404, 6),
            "116.404000"
        )
    }

    @Test
    fun deviceId_mustBe20Digits() {
        assertFailsWith<IllegalArgumentException> { VideoUploadNotify.build(1, "dev", "t") }
    }

    @Test
    fun sn_mustBePositive() {
        assertFailsWith<IllegalArgumentException> { VideoUploadNotify.build(0, deviceId, "t") }
    }

    // ---- 往返（供平台侧实现 / 本仓测试对账） ----

    @Test
    fun roundTrip_withCoordinates() {
        val xml = VideoUploadNotify.build(
            12, deviceId, "2026-09-19T08:40:00",
            longitude = 116.404, latitude = 39.915
        )
        val parsed = VideoUploadNotify.parse(xml)!!
        assertEquals(12, parsed.sn)
        assertEquals(deviceId, parsed.deviceId)
        assertEquals("2026-09-19T08:40:00", parsed.timeIso)
        assertEquals(116.404, parsed.longitude!!, 1e-9)
        assertEquals(39.915, parsed.latitude!!, 1e-9)
    }

    @Test
    fun roundTrip_withoutCoordinates_leavesThemNull() {
        val parsed = VideoUploadNotify.parse(VideoUploadNotify.build(12, deviceId, "2026-09-19T08:40:00"))!!
        assertNull(parsed.longitude, "缺席 ≠ 0.0")
        assertNull(parsed.latitude)
    }

    /**
     * ⛔ 解析器**不硬编码**编码声明前缀：出站字符集按有效版本走（2016 GB2312 / 2022 GB18030），
     * 写死 `<?xml ... encoding="GB2312"?>` 会在切版本时"静默剥不掉"，表现为解析恒 null。
     */
    @Test
    fun parse_acceptsEitherCharsetDeclaration() {
        val gb2312 = VideoUploadNotify.build(12, deviceId, "2026-09-19T08:40:00")
        val gb18030 = gb2312.replace("encoding=\"UTF-8\"", "encoding=\"GB18030\"")
        assertTrue(VideoUploadNotify.parse(gb18030) != null, "GB18030 声明也要能解: $gb18030")
        val withoutDecl = gb2312.substringAfter("?>").trim()
        assertTrue(VideoUploadNotify.parse(withoutDecl) != null, "没有声明也要能解")
    }

    @Test
    fun parse_missingTime_returnsNull() {
        val xml = VideoUploadNotify.build(12, deviceId, "2026-09-19T08:40:00")
            .replace("<Time>2026-09-19T08:40:00</Time>\r\n", "")
            .replace("<Time>2026-09-19T08:40:00</Time>\n", "")
        assertNull(VideoUploadNotify.parse(xml), "Time 必选，缺失应判非法")
    }

    @Test
    fun parse_wrongCmdType_returnsNull() {
        val xml = VideoUploadNotify.build(12, deviceId, "2026-09-19T08:40:00")
            .replace("VideoUploadNotify", "Alarm")
        assertNull(VideoUploadNotify.parse(xml))
    }

    @Test
    fun parse_badDeviceId_returnsNull() {
        val xml = VideoUploadNotify.build(12, deviceId, "2026-09-19T08:40:00")
            .replace(deviceId, "dev")
        assertNull(VideoUploadNotify.parse(xml))
    }
}

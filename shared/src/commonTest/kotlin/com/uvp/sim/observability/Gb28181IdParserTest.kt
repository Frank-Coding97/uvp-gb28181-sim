package com.uvp.sim.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * GB28181 ID 是 20 位:行政区划(6) + 行业编码(2) + 类型编码(3) + 网络标识(1) + 序号(8)。
 *
 * 类型编码常见:
 *   - 132 = 视频通道
 *   - 134 = 报警通道
 *   - 200 = 平台
 *   - 111 = 设备
 *
 * 老板真机上看"通道 001"识别度为 0(末 3 位都是 001),需要拆出类型 + 序号末 4 位。
 */
class Gb28181IdParserTest {

    @Test
    fun parses_video_channel_id() {
        // 34 0200 00 0 132 0 00000001
        // 行政区划 340200, 行业 00, 类型 132 (视频), 网络 0, 序号 00000001
        val parsed = Gb28181IdParser.parse("34020000001320000001")
        assertEquals("视频", parsed?.typeName)
        assertEquals("0001", parsed?.serialShort)
        assertEquals("视频通道 0001", parsed?.label)
    }

    @Test
    fun parses_alarm_channel_id() {
        val parsed = Gb28181IdParser.parse("34020000001340000001")
        assertEquals("报警", parsed?.typeName)
        assertEquals("报警通道 0001", parsed?.label)
    }

    @Test
    fun parses_device_id() {
        val parsed = Gb28181IdParser.parse("34020000001110000001")
        assertEquals("设备", parsed?.typeName)
        assertEquals("设备 0001", parsed?.label)
    }

    @Test
    fun parses_platform_id() {
        val parsed = Gb28181IdParser.parse("34020000002000000001")
        assertEquals("平台", parsed?.typeName)
        assertEquals("平台 0001", parsed?.label)
    }

    @Test
    fun unknown_type_falls_back_to_raw_type_code() {
        // 999 不是已知类型
        val parsed = Gb28181IdParser.parse("34020000009990000001")
        assertEquals("类型 999", parsed?.typeName)
        assertEquals("类型 999 · 0001", parsed?.label)
    }

    @Test
    fun returns_null_for_short_id() {
        assertNull(Gb28181IdParser.parse("12345"))
        assertNull(Gb28181IdParser.parse(""))
    }

    @Test
    fun returns_null_for_non_digit_id() {
        assertNull(Gb28181IdParser.parse("3402000000abc20000001"))
    }

    @Test
    fun extract_from_request_uri_strips_sip_prefix_and_domain() {
        // SIP requestUri 形如 sip:34020000001320000001@3402000000
        val parsed = Gb28181IdParser.parseFromRequestUri("sip:34020000001320000001@3402000000")
        assertEquals("视频通道 0001", parsed?.label)
    }

    @Test
    fun extract_from_request_uri_handles_no_at_sign() {
        // 兜底:有些平台只发 ID 部分
        val parsed = Gb28181IdParser.parseFromRequestUri("34020000001320000001")
        assertEquals("视频通道 0001", parsed?.label)
    }

    @Test
    fun extract_from_request_uri_returns_null_on_garbage() {
        assertNull(Gb28181IdParser.parseFromRequestUri("sip:invalid@example.com"))
        assertNull(Gb28181IdParser.parseFromRequestUri(""))
    }
}

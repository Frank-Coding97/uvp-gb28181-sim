package com.uvp.sim.sip

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class SipDateParserTest {

    // RFC1123 标准格式(WVP / 自家 SipBuilders.rfc1123Date 都用这个)
    @Test
    fun `parses RFC1123 GMT date`() {
        val s = "Wed, 18 Jun 2026 07:30:00 GMT"
        val r = SipDateParser.parse(s)
        assertNotNull(r)
        assertEquals(Instant.parse("2026-06-18T07:30:00Z"), r)
    }

    // ISO8601 带 Z 后缀
    @Test
    fun `parses ISO8601 with Z suffix`() {
        val s = "2026-06-18T07:30:00.000Z"
        val r = SipDateParser.parse(s)
        assertEquals(Instant.parse("2026-06-18T07:30:00Z"), r)
    }

    // ISO8601 带时区偏移
    @Test
    fun `parses ISO8601 with timezone offset`() {
        val s = "2026-06-18T15:30:00+08:00"
        val r = SipDateParser.parse(s)
        assertEquals(Instant.parse("2026-06-18T07:30:00Z"), r)
    }

    // ISO8601 无时区(WVP 实测格式)→ 按系统默认时区解析
    @Test
    fun `parses ISO8601 without timezone using system zone`() {
        val s = "2026-06-18T16:26:57.492"
        val r = SipDateParser.parse(s)
        assertNotNull(r)
        // 不锁绝对值(测试机时区可能不同),仅断言"等价于按系统时区把 LocalDateTime 翻译成 Instant"
        val expected = LocalDateTime.parse(s).toInstant(TimeZone.currentSystemDefault())
        assertEquals(expected, r)
    }

    @Test
    fun `null returns null`() {
        assertNull(SipDateParser.parse(null))
    }

    @Test
    fun `empty returns null`() {
        assertNull(SipDateParser.parse(""))
        assertNull(SipDateParser.parse("   "))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(SipDateParser.parse("not-a-date"))
    }

    @Test
    fun `partial date returns null`() {
        assertNull(SipDateParser.parse("2026-06-18"))
    }
}

package com.uvp.sim.osd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OsdGlMathTest {

    // ===== parseHexColorArgb =====

    @Test
    fun rrggbbReturnsOpaqueWhite() {
        assertEquals(0xFFFFFFFF.toInt(), parseHexColorArgb("#FFFFFF"))
    }

    @Test
    fun rrggbbReturnsOpaqueRed() {
        assertEquals(0xFFFF0000.toInt(), parseHexColorArgb("#FF0000"))
    }

    @Test
    fun rrggbbReturnsOpaqueYellow() {
        assertEquals(0xFFFFFF00.toInt(), parseHexColorArgb("#FFFF00"))
    }

    @Test
    fun aarrggbbPreservesAlpha() {
        assertEquals(0x80FF0000.toInt(), parseHexColorArgb("#80FF0000"))
    }

    @Test
    fun lowercaseHexAccepted() {
        assertEquals(0xFFAABBCC.toInt(), parseHexColorArgb("#aabbcc"))
    }

    @Test
    fun mixedCaseHexAccepted() {
        assertEquals(0xFFAABBCC.toInt(), parseHexColorArgb("#aaBBcc"))
    }

    @Test
    fun missingHashStillWorks() {
        assertEquals(0xFFFF0000.toInt(), parseHexColorArgb("FF0000"))
    }

    @Test
    fun garbageReturnsWhite() {
        assertEquals(0xFFFFFFFF.toInt(), parseHexColorArgb("#GGHHII"))
    }

    @Test
    fun emptyStringReturnsWhite() {
        assertEquals(0xFFFFFFFF.toInt(), parseHexColorArgb(""))
    }

    @Test
    fun shortHexReturnsWhite() {
        // "#FFF" 不属于支持格式(只支持 6/8 位),返回 fallback
        assertEquals(0xFFFFFFFF.toInt(), parseHexColorArgb("#FFF"))
    }

    @Test
    fun whitespaceTrimmed() {
        assertEquals(0xFFFF0000.toInt(), parseHexColorArgb("  #FF0000  "))
    }

    // ===== pixelToNdcX =====

    @Test
    fun pixelXZeroMapsToMinusOne() {
        assertEquals(-1f, pixelToNdcX(0f, 1280), 0.001f)
    }

    @Test
    fun pixelXMaxMapsToOne() {
        assertEquals(1f, pixelToNdcX(1280f, 1280), 0.001f)
    }

    @Test
    fun pixelXMidMapsToZero() {
        assertEquals(0f, pixelToNdcX(640f, 1280), 0.001f)
    }

    @Test
    fun pixelXZeroViewportReturnsZero() {
        // 不崩,返回 0
        assertEquals(0f, pixelToNdcX(100f, 0), 0.001f)
    }

    // ===== pixelToNdcY (Y 翻转,屏幕朝下,GL 朝上) =====

    @Test
    fun pixelYZeroMapsToOne() {
        // 屏幕顶端 → GL NDC 顶端 1
        assertEquals(1f, pixelToNdcY(0f, 720), 0.001f)
    }

    @Test
    fun pixelYMaxMapsToMinusOne() {
        // 屏幕底端 → GL NDC 底端 -1
        assertEquals(-1f, pixelToNdcY(720f, 720), 0.001f)
    }

    @Test
    fun pixelYMidMapsToZero() {
        assertEquals(0f, pixelToNdcY(360f, 720), 0.001f)
    }

    @Test
    fun pixelYZeroViewportReturnsZero() {
        assertEquals(0f, pixelToNdcY(100f, 0), 0.001f)
    }

    // 浮点比较 helper
    private fun assertEquals(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected=$expected, actual=$actual, tolerance=$tolerance"
        )
    }
}

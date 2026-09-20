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

    // ===== cameraQuadVertices（画面翻转 A.2.1.23 的顶点层）=====
    //
    // ⭐ 这一组是本功能**唯一容易做错又不报错**的地方，所以每条都钉死语义：
    //    翻的是顶点位置，uv 一个字节都不许动。

    @Test
    fun cameraQuadWithoutMirrorKeepsLegacyLayout() {
        // 无翻转 = 与加本功能之前逐字节一致（默认行为不变的锚点）
        assertFloatArrayEquals(
            floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f,
            ),
            cameraQuadVertices(1f, 1f, DEFAULT_CAMERA_QUAD_UV),
        )
    }

    @Test
    fun cameraQuadKeepsCenterCropScale() {
        // center-crop 的缩放要仍然乘在位置里（翻转不能把它吃掉）
        val v = cameraQuadVertices(1.5f, 2f, DEFAULT_CAMERA_QUAD_UV)
        assertEquals(-1.5f, v[0], 0.0001f)
        assertEquals(-2f, v[1], 0.0001f)
        assertEquals(1.5f, v[4], 0.0001f)
    }

    @Test
    fun mirrorXFlipsPositionsAndLeavesUvUntouched() {
        val base = cameraQuadVertices(1f, 1f, DEFAULT_CAMERA_QUAD_UV)
        val flipped = cameraQuadVertices(1f, 1f, DEFAULT_CAMERA_QUAD_UV, mirrorX = true)
        for (i in 0 until 4) {
            val off = i * 4
            assertEquals(-base[off], flipped[off], 0.0001f)          // x 取反
            assertEquals(base[off + 1], flipped[off + 1], 0.0001f)   // y 不变
            assertEquals(base[off + 2], flipped[off + 2], 0.0001f)   // u 不变 ⛔
            assertEquals(base[off + 3], flipped[off + 3], 0.0001f)   // v 不变 ⛔
        }
    }

    @Test
    fun mirrorYFlipsPositionsAndLeavesUvUntouched() {
        val base = cameraQuadVertices(1f, 1f, DEFAULT_CAMERA_QUAD_UV)
        val flipped = cameraQuadVertices(1f, 1f, DEFAULT_CAMERA_QUAD_UV, mirrorY = true)
        for (i in 0 until 4) {
            val off = i * 4
            assertEquals(base[off], flipped[off], 0.0001f)
            assertEquals(-base[off + 1], flipped[off + 1], 0.0001f)
            assertEquals(base[off + 2], flipped[off + 2], 0.0001f)
            assertEquals(base[off + 3], flipped[off + 3], 0.0001f)
        }
    }

    @Test
    fun mirrorBothFlipsBothAxes() {
        // A.2.1.23 的 3 = 中心镜像 = 两个方向都翻
        val base = cameraQuadVertices(1f, 1f, DEFAULT_CAMERA_QUAD_UV)
        val flipped = cameraQuadVertices(
            1f, 1f, DEFAULT_CAMERA_QUAD_UV, mirrorX = true, mirrorY = true,
        )
        for (i in 0 until 4) {
            val off = i * 4
            assertEquals(-base[off], flipped[off], 0.0001f)
            assertEquals(-base[off + 1], flipped[off + 1], 0.0001f)
        }
    }

    @Test
    fun cameraQuadPassesUvThroughInPlace() {
        // ⛔ 抓"顺手重排 uv"：输入什么顺序，输出就什么顺序（不旋转、不交换）
        val rotated = floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f)
        val v = cameraQuadVertices(1f, 1f, rotated, mirrorX = true, mirrorY = true)
        assertFloatArrayEquals(rotated, floatArrayOf(v[2], v[3], v[6], v[7], v[10], v[11], v[14], v[15]))
    }

    @Test
    fun cameraQuadFallsBackToDefaultUvWhenTooShort() {
        // 长度不对时用默认 uv，不越界崩
        val v = cameraQuadVertices(1f, 1f, floatArrayOf(0f, 0f, 0f))
        assertEquals(0f, v[2], 0.0001f)
        assertEquals(1f, v[3], 0.0001f)
    }

    // 浮点比较 helper
    private fun assertEquals(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected=$expected, actual=$actual, tolerance=$tolerance"
        )
    }

    private fun assertFloatArrayEquals(
        expected: FloatArray,
        actual: FloatArray,
        tolerance: Float = 0.0001f,
    ) {
        assertEquals(expected.size, actual.size, "size mismatch")
        for (i in expected.indices) {
            assertTrue(
                kotlin.math.abs(expected[i] - actual[i]) <= tolerance,
                "index $i: expected=${expected[i]}, actual=${actual[i]}"
            )
        }
    }
}

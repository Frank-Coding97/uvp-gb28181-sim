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

    // ===== cameraQuadVertices × 拉框视窗（A.2.3.1.8/.9）=====

    @Test
    fun cropIdentityIsByteIdenticalToTheLegacyPath() {
        // "平台没下发过拉框 ⇒ 逐帧与加本功能之前一致"必须是**字节级**的：
        // 恒等视窗走原公式（顶点 ±frameScale、uv 原样透传），而不是"用新公式算一遍恒等值"。
        val uvs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val legacy = cameraQuadVertices(1.5f, 2f, uvs, mirrorX = true, mirrorY = true)
        val withCrop = cameraQuadVertices(
            1.5f, 2f, uvs,
            mirrorX = true, mirrorY = true,
            crop = VideoDragZoomViewport.IDENTITY,
        )
        assertFloatArrayEquals(legacy, withCrop, tolerance = 0f)
    }

    @Test
    fun cropMagnifiesByShrinkingTheSampledUvRange() {
        // frameScale = 1（画框与相机画面同比例）⇒ 裁剪窗就是 uv 区间本身。
        val uvs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val v = cameraQuadVertices(1f, 1f, uvs, crop = VideoDragZoomViewport(0.25f, 0.25f, 0.5f, 0.5f))
        // 顶点仍铺满画框（±1）—— 放大是靠 uv 做的
        assertFloatArrayEquals(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            floatArrayOf(v[0], v[1], v[4], v[5], v[8], v[9], v[12], v[13]),
        )
        // ⭐ 判定口径一律用**编码帧**（= 操作员在平台上看到的画面）说：
        //    编码帧左上角该采样显示框的左上角 → (u=0.25, v=0.75)
        //    编码帧右下角该采样显示框的右下角 → (u=0.75, v=0.25)
        // ⛔ 纵向 v 比横向多两层（折回 + blit 换位），所以「渲染帧左下顶点」身上挂的是
        //    **显示上沿**的 v（0.75）、「渲染帧右上顶点」挂的是显示下沿的 v（0.25）——
        //    看着像写反了，那正是 blit 上下翻的兑现点，见 cameraQuadVertices KDoc。
        assertEquals(0.25f, v[2], 0.0001f)          // 渲染左下顶点的 u = 显示框左沿
        assertEquals(0.75f, v[14], 0.0001f)         // 渲染右上顶点的 u = 显示框右沿
        assertEquals(0.75f, vAtEncodedTop(v), 0.0001f)     // 编码帧顶行 ⛔
        assertEquals(0.25f, vAtEncodedBottom(v), 0.0001f)  // 编码帧底行 ⛔
    }

    @Test
    fun cropKeepsTheVerticalOrderUpright() {
        // ⛔ 抓"位置搬对了、内容却上下颠倒"：编码帧**顶行**必须看到显示框的**上沿**。
        //    sy=2 时未裁剪路径的"显示行 d → 采到 v"是 legacyV(d)=0.75-0.5d
        //    （d=0 顶行采 0.75、d=1 底行采 0.25），所以"取错带"和"内容倒置"会给出不同数字。
        val uvs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val v = cameraQuadVertices(1f, 2f, uvs, crop = VideoDragZoomViewport(0f, 0f, 1f, 0.5f))
        // 编码帧顶行 → 应采显示行 0 的 v = 0.75
        assertEquals(0.75f, vAtEncodedTop(v), 0.0001f)
        // 编码帧底行 → 应采显示行 0.5 的 v = 0.5
        assertEquals(0.5f, vAtEncodedBottom(v), 0.0001f)
    }

    @Test
    fun cropVerticalAnchorMatchesTheRealMachineOffCenterMeasurement() {
        // ⭐ 真机锚点（2026-09-20，偏心框）：sy = 16/9、平台框 top = 1/8、h = 1/3。
        //
        // 为什么必须是**偏心**框：中心对称的框在上下镜像下不变，中心框验证"通过"是假绿。
        //
        // 真机对账（ZLM 取流逐帧差分定位切点 + 相邻帧相似变换）：
        //   旧实现把编码帧顶行搬成了源帧第 392 行（0.544）、底行第 632 行（0.878），
        //   即 [1/2+1/24, 1-1/8] —— 正好是请求区间 [1/8, 1/8+1/3] 的上下镜像。
        val sy = 16f / 9f
        val crop = VideoDragZoomViewport(1f / 6f, 1f / 8f, 1f / 3f, 1f / 3f)
        val v = cameraQuadVertices(1f, sy, DEFAULT_CAMERA_QUAD_UV, crop = crop)
        // 未裁剪路径的映射（真机上的"尺子"，不复用本函数的内部量）：
        // 显示行 d 采到的 v = (1 + (1-2d)/sy)/2；反过来由采到 v 求显示行 = (1 - sy*(2v-1))/2
        fun displayRowOf(sampledV: Float) = (1f - sy * (2f * sampledV - 1f)) / 2f
        assertEquals(crop.top, displayRowOf(vAtEncodedTop(v)), 0.001f)
        assertEquals(crop.bottom, displayRowOf(vAtEncodedBottom(v)), 0.001f)
    }

    @Test
    fun cropFoldsTheVerticalAxisWhenMirrored() {
        // mirror 时框是画在**已镜像**的画面上 ⇒ 编码帧顶行该看到的是**源画面**（未镜像口径）的
        // 行 (1 - crop.top)。⛔ 别把这条写成"编码帧顶行采到的 v 与不镜像时相同" ——
        // 折回本来就会改变采样点（写成那样会误报代码错，实际错的是前提）。
        val sy = 2f
        val crop = VideoDragZoomViewport(0f, 1f / 8f, 1f, 1f / 3f)
        val v = cameraQuadVertices(1f, sy, DEFAULT_CAMERA_QUAD_UV, mirrorY = true, crop = crop)
        // 未裁剪路径的映射 legacyV(d) = (1 + (1-2d)/sy)/2 ⇒ 由采到的 v 反解源行 d
        fun sourceRowOf(sampledV: Float) = (1f - sy * (2f * sampledV - 1f)) / 2f
        assertEquals(1f - crop.top, sourceRowOf(vAtEncodedTop(v)), 0.001f)
        assertEquals(1f - crop.bottom, sourceRowOf(vAtEncodedBottom(v)), 0.001f)
    }

    @Test
    fun cropCarriesTheCenterCropScale() {
        // ⛔ 画框只看到相机画面中央 1/frameScale 那一条（cropToFill 的 center-crop），
        //    所以"屏幕上的 1/4"不等于"相机画面的 1/4"：漏掉这个尺度会整体错位。
        //    frameScale = 2 ⇒ 屏幕 [0.25, 0.75] 对应相机 [0.375, 0.625]。
        val uvs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val v = cameraQuadVertices(2f, 1f, uvs, crop = VideoDragZoomViewport(0.25f, 0f, 0.5f, 1f))
        // 顶点 0(屏幕左沿) 的 u 在 v[2]；顶点 3(屏幕右沿) 的 u 在 v[14]（⛔ v[12] 是它的 x 位置）
        assertEquals(0.375f, v[2], 0.0001f)
        assertEquals(0.625f, v[14], 0.0001f)
    }

    @Test
    fun cropFollowsTheDisplayedImageWhenMirrored() {
        // ⛔⛔ 组合锚点：裁剪框是操作员在**已镜像**的画面上框的 ⇒ 屏幕左沿要采样源画面的
        //     **右**半边。这里镜像 + 只取显示左 1/4 ⇒ 采样源 [0.5, 0.75]，
        //     且屏幕左沿拿到 0.75（源右）、屏幕右沿拿到 0.5。
        //     把镜像折回与 frameScale 换算合成一步/顺序颠倒，这条就会红
        //     —— 而单开镜像、单开裁剪各自的用例都是绿的。
        val uvs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val crop = VideoDragZoomViewport(0.25f, 0f, 0.25f, 1f)
        val v = cameraQuadVertices(1f, 1f, uvs, mirrorX = true, crop = crop)
        // 顶点 1(base +1) 被镜像到屏幕左沿，顶点 0(base -1) 到屏幕右沿
        assertEquals(-1f, v[4], 0.0001f)
        assertEquals(0.75f, v[6], 0.0001f)
        assertEquals(1f, v[0], 0.0001f)
        assertEquals(0.5f, v[2], 0.0001f)
    }

    // ===== 拉框用例的公共判据：一律用**编码帧**说事 =====
    //
    // ⛔ 本 pass 之后 blit 时上下会再翻一次 ⇒ 编码帧**顶行**看到的是渲染帧**下沿**顶点的 uv。
    //    所以这两个 helper 按**几何**（NDC y 的正负）取顶点，不按固定下标 ——
    //    `mirrorY` 会把顶点位置翻过来，按下标写必然在镜像用例上误报。

    /** 编码帧顶行采到的 uv v（= 渲染帧下沿顶点携带的）。 */
    private fun vAtEncodedTop(v: FloatArray): Float {
        val i = (0 until 4).first { v[it * 4 + 1] < 0f }
        return v[i * 4 + 3]
    }

    /** 编码帧底行采到的 uv v（= 渲染帧上沿顶点携带的）。 */
    private fun vAtEncodedBottom(v: FloatArray): Float {
        val i = (0 until 4).first { v[it * 4 + 1] > 0f }
        return v[i * 4 + 3]
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

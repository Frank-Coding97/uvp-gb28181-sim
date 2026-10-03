package com.uvp.sim.osd

import com.uvp.sim.domain.DragZoomRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 拉框放大/缩小（GB/T 28181-2022 A.2.3.1.8/.9）的设备侧语义。
 *
 * ⛔ 坐标口径的唯一出处是标准附录 A 的注：「命令中的坐标系以播放窗口的左上角原点，
 * 各坐标取值以像素单位」—— 不是 0~1000 归一化。所以每一条换算用例都带**播放窗口尺寸**
 * （标准里的 `Length` / `Width`），分母用错就没有一条能过。
 */
class VideoDragZoomTest {

    private fun rect(
        midX: Int,
        midY: Int,
        lengthX: Int,
        lengthY: Int,
        frameLength: Int = 1000,
        frameWidth: Int = 500,
    ) = DragZoomRect(midX, midY, lengthX, lengthY, frameLength, frameWidth)

    // ===== 归一化：比值换算，与播放窗口大小无关 =====

    @Test
    fun boxIsNormalizedByPlaybackWindow() {
        // 窗口 1000x500（非正方形），框中心 (250,125)、大小 200x100
        val box = assertNotNull(DragZoomBox.of(rect(250, 125, 200, 100)))
        assertEquals(0.15f, box.left, 0.0001f) // 250/1000 - 200/1000/2
        assertEquals(0.15f, box.top, 0.0001f) // 125/500  - 100/500/2
        assertEquals(0.2f, box.width, 0.0001f) // 200/1000
        assertEquals(0.2f, box.height, 0.0001f) // 100/500
    }

    @Test
    fun windowScaleDoesNotChangeTheNormalizedBox() {
        // ⭐ 平台发的是一组**比值**，所以窗口大 10 倍（同一块画面）归一化结果必须一模一样。
        //    这条成立，设备端才不需要知道"播放窗口到底多大"。
        val a = assertNotNull(DragZoomBox.of(rect(250, 125, 200, 100, frameLength = 1000, frameWidth = 500)))
        val b = assertNotNull(DragZoomBox.of(rect(2500, 1250, 2000, 1000, frameLength = 10000, frameWidth = 5000)))
        assertEquals(a.left, b.left, 0.0001f)
        assertEquals(a.top, b.top, 0.0001f)
        assertEquals(a.width, b.width, 0.0001f)
        assertEquals(a.height, b.height, 0.0001f)
    }

    @Test
    fun missingPlaybackWindowSizeIsNotConvertible() {
        // 2026-09-20 前的实现就是这一档：只收 4 个框字段、丢掉两把尺子 ⇒ 永远算不出归一化坐标。
        assertNull(DragZoomBox.of(rect(250, 125, 200, 100, frameLength = 0, frameWidth = 0)))
        assertNull(DragZoomBox.of(rect(250, 125, 200, 100, frameLength = 1000, frameWidth = 0)))
    }

    @Test
    fun degenerateFrameIsDroppedNotCropped() {
        // 退化框整条丢弃：拿它去裁一次画面会把画面锁死在一个角上，而标准里没有复位命令。
        assertNull(DragZoomBox.of(rect(250, 125, 0, 100)))
        assertNull(DragZoomBox.of(rect(250, 125, 200, 0)))
    }

    @Test
    fun boxOutsideTheFrameIsDropped() {
        // 整块落在画面外（左/上之外）⇒ 与画面求交后没有面积，只能丢。
        assertNull(DragZoomBox.of(rect(-100, -50, 100, 50, frameLength = 1000, frameWidth = 500)))
    }

    @Test
    fun boxPartlyOutsideIsClampedInside() {
        val box = assertNotNull(DragZoomBox.of(rect(0, 0, 400, 200)))
        assertEquals(0f, box.left, 0.0001f)
        assertEquals(0f, box.top, 0.0001f)
        assertEquals(0.2f, box.width, 0.0001f)
        assertEquals(0.2f, box.height, 0.0001f)
    }

    // ===== 累积：第二次放大是在"当前视窗"里再裁 =====

    @Test
    fun zoomInCropsRelativeToCurrentViewport() {
        val first = VideoDragZoomViewport.IDENTITY.zoomIn(box(0.4f, 0.4f, 0.2f, 0.2f))
        assertEquals(0.4f, first!!.left, 0.0001f)
        assertEquals(0.2f, first.width, 0.0001f)
        // 第二次：这里的框是"画在已放大画面上"的，所以是在 0.4~0.6 这块里再取右下四分之一。
        val second = first.zoomIn(box(0.5f, 0.5f, 0.5f, 0.5f))
        assertEquals(0.5f, second!!.left, 0.0001f)
        assertEquals(0.5f, second.top, 0.0001f)
        assertEquals(0.1f, second.width, 0.0001f)
        assertEquals(0.1f, second.height, 0.0001f)
    }

    @Test
    fun zoomInBeyondMinimumWindowIsRejected() {
        // 1/32 边长是下限：再往下只是把同样的像素放大，却能让一次误操作锁死视野。
        val overLimit = VideoDragZoomViewport(0f, 0f, 1f / 32f, 1f / 32f)
        assertNull(overLimit.zoomIn(box(0f, 0f, 0.5f, 0.5f)))
        // 边界值本身允许（`>= MIN_WINDOW` 就算通过），免得"刚好一档"被误拒。
        val atLimit = VideoDragZoomViewport(0f, 0f, 1f / 16f, 1f / 16f)
        assertNotNull(atLimit.zoomIn(box(0f, 0f, 0.5f, 0.5f)))
    }

    @Test
    fun zoomInThenZoomOutWithTheSameBoxIsIdentity() {
        // ⭐ 不变量：同一个框先放大再缩小，必须回到原来的视窗。
        //    这条把"放大 = 视窗取子块 / 缩小 = 视窗反向放大"两边的公式钉在一起 ——
        //    任一边写错（比如缩小按中心对齐而不是按框对齐）这里就会漂。
        val start = VideoDragZoomViewport(0.2f, 0.1f, 0.4f, 0.3f)
        val b = box(0.25f, 0.25f, 0.5f, 0.5f)
        val roundTrip = start.zoomIn(b)!!.zoomOut(b)
        assertEquals(start.left, roundTrip.left, 0.0001f)
        assertEquals(start.top, roundTrip.top, 0.0001f)
        assertEquals(start.width, roundTrip.width, 0.0001f)
        assertEquals(start.height, roundTrip.height, 0.0001f)
    }

    @Test
    fun zoomOutPutsTheCurrentWindowExactlyIntoTheBox() {
        // 标准：「拉框缩小命令将整个播放窗口的图像缩小到播放窗口选定框内」
        // ⇒ 缩小后，**当前这幅画面**应当正好落在框的位置上（不是中心对齐 —— 框偏在角上时
        //    中心对齐会把画面挪走，用户看到的位置与他框的位置对不上）。
        val start = VideoDragZoomViewport(0.4f, 0.4f, 0.1f, 0.1f)
        val b = box(0.25f, 0.5f, 0.25f, 0.25f) // 框偏在左侧
        val out = start.zoomOut(b)
        assertEquals(0.4f, out.width, 0.0001f) // 0.1 / 0.25
        assertEquals(0.4f, out.height, 0.0001f)
        // 当前视窗在**新视窗坐标系**里必须正好等于框
        val mappedLeft = (start.left - out.left) / out.width
        val mappedRight = (start.right - out.left) / out.width
        assertEquals(b.left, mappedLeft, 0.0001f)
        assertEquals(b.left + b.width, mappedRight, 0.0001f)
    }

    @Test
    fun zoomOutAtTheWidestClampsToTheWholeFrame() {
        // 已经到最广（整幅）还往外缩 ⇒ 夹回整幅、位置归零；**不引入黑边**（铺满才与标准一致）。
        val out = VideoDragZoomViewport(0.25f, 0.25f, 0.5f, 0.5f).zoomOut(box(0.4f, 0.4f, 0.2f, 0.2f))
        assertEquals(1f, out.width, 0.0001f)
        assertEquals(1f, out.height, 0.0001f)
        assertEquals(0f, out.left, 0.0001f)
        assertEquals(0f, out.top, 0.0001f)
        assertEquals(VideoDragZoomViewport.IDENTITY, out)
    }

    @Test
    fun viewportStaysInsideTheSource() {
        // 渲染端不再夹一次：视窗出界必须是设备侧就挡掉的事（出界在 GL 上是采样到边缘像素/黑边）。
        val clamped = VideoDragZoomViewport(0.9f, 0.9f, 0.4f, 0.4f).clampedToSource()
        assertEquals(0.9f, clamped.left, 0.0001f)
        assertEquals(0.1f, clamped.width, 0.0001f)
    }

    @Test
    fun identityIsRecognisedSoTheRendererCanSkipTheBranch() {
        // 渲染端靠这个判断走"原路径"（字节级与加本功能之前一致），认错就等于永远多进一次分支。
        assertEquals(true, VideoDragZoomViewport(0f, 0f, 1f, 1f).isIdentity)
        assertEquals(false, VideoDragZoomViewport(0f, 0f, 1f, 0.999f).isIdentity)
        assertEquals(false, VideoDragZoomViewport(0.001f, 0f, 0.999f, 1f).isIdentity)
    }

    private fun box(left: Float, top: Float, width: Float, height: Float) =
        DragZoomBox(left, top, width, height)
}

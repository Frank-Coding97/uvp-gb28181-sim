package com.uvp.sim.domain

import com.uvp.sim.gb28181.TargetArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GB/T 28181-2022 A.2.3.1.14 目标跟踪在**设备侧**的两件事：
 *  1. [TargetTrackBox.of] —— 平台报文像素 → 画面归一化框（比值换算，尺子不能拿反）；
 *  2. [TargetTrackState.of] —— 一条指令 → 设备侧跟踪态（`Auto` 与 `Manual` 的框来源**不同**）。
 *
 * ⛔ 这两个函数是"平台上框在哪 → 设备屏幕上框在哪"的**唯一**换算点。测试盯的是三类历史坑：
 *  - **尺子拿反**：`Length` 是播放窗口**长度**（横向）、`Width` 是**宽度**（纵向）——
 *    名字都像"宽"，反了不报错，只在非正方形画面上横向错位；
 *  - **半份坐标**：六个子元素缺一个就必须整体拒绝，不能拿半份去画框；
 *  - **假的"框选成功"**：`Manual` 没有可用框时**绝不**回落成 [TargetTrackState.SIMULATED_AUTO_BOX]。
 */
class TargetTrackStateTest {

    /** 1920×1080 播放窗口、正中 200×100 的框 —— 标准 A.2.3.1.14 的典型形态。 */
    private fun areaOf(
        length: Int = 1920,
        width: Int = 1080,
        midPointX: Int = 960,
        midPointY: Int = 540,
        lengthX: Int = 200,
        lengthY: Int = 100,
    ) = TargetArea(length, width, midPointX, midPointY, lengthX, lengthY)

    private fun assertNear(expected: Float, actual: Float, tag: String) {
        assertTrue(kotlin.math.abs(expected - actual) < 1e-4f, "$tag 期望=$expected 实际=$actual")
    }

    @Test
    fun `框选换算 —— 横轴除以 Length、纵轴除以 Width`() {
        val box = assertNotNull(TargetTrackBox.of(areaOf()))
        // 横轴：按 Length=1920 换算
        assertNear(0.5f - (200f / 1920f) / 2f, box.left, "left")
        assertNear(200f / 1920f, box.width, "width")
        // 纵轴：按 Width=1080 换算
        assertNear(0.5f - (100f / 1080f) / 2f, box.top, "top")
        assertNear(100f / 1080f, box.height, "height")
        // 两条尺子**必须**分别起作用：拿反了这里会差出 1.78 倍（1920/1080）。
        assertNear(0.5f, box.centerX, "centerX")
        assertNear(0.5f, box.centerY, "centerY")
    }

    @Test
    fun `框选换算 —— 非正方形窗口下方框不可能是正方形`() {
        // 200×200 的像素框在 1920×1080 窗口里是"扁"的：宽 10.4%、高 18.5%。
        val box = assertNotNull(TargetTrackBox.of(areaOf(lengthX = 200, lengthY = 200)))
        assertTrue(box.width < box.height, "1600:900 的窗口里等像素框必须更窄，实际 w=${box.width} h=${box.height}")
        assertNear(0.1042f, box.width, "width")
        assertNear(0.1852f, box.height, "height")
    }

    @Test
    fun `缺尺子 —— Length 或 Width 非正时整体拒绝`() {
        assertNull(TargetTrackBox.of(areaOf(length = 0)), "没有横向尺子就算不出横轴")
        assertNull(TargetTrackBox.of(areaOf(width = 0)), "没有纵向尺子就算不出纵轴")
    }

    @Test
    fun `退化框 —— LengthX 或 LengthY 非正时整体拒绝`() {
        assertNull(TargetTrackBox.of(areaOf(lengthX = 0)))
        assertNull(TargetTrackBox.of(areaOf(lengthY = 0)))
    }

    @Test
    fun `整框在画面外 —— 求交后没有面积则拒绝，而不是画到画面外`() {
        // 中心点 x 已经跑到 5000px（远在 1920 之外），框完全落在画面右侧之外。
        assertNull(TargetTrackBox.of(areaOf(midPointX = 5000)))
        assertNull(TargetTrackBox.of(areaOf(midPointY = -400)))
    }

    @Test
    fun `部分出界 —— 夹取到画面内(贴边且更小)，中心随之偏移`() {
        // 中心正好落在画面左边界上、框宽 400px ⇒ **一半在画面外**（那半根本不存在）。
        // 夹取后只留画面内的 200px：left=0、width=200/1920、中心也在半宽处。
        val box = assertNotNull(TargetTrackBox.of(areaOf(midPointX = 0, lengthX = 400)))
        assertNear(0f, box.left, "左边界必须贴到 0")
        assertNear(200f / 1920f, box.width, "只保留画面内的那一半（400px 的一半）")
        assertNear(100f / 1920f, box.centerX, "夹取后中心 = 半宽，不是原来的 0")
        // ⚠️ 夹取**会**让中心从 0 偏到半宽处 —— 这是刻意的取舍（画到画面外的那半根本不存在），
        //    与 `DragZoomBox.clampedToSource` 同一条规矩：与其留一个"看起来框在那边"的假象，
        //    不如如实缩小。
    }

    @Test
    fun `框比画面还大 —— 夹取成整幅，不报错也不越界`() {
        // 框宽 2880px > 1920px 窗口 ⇒ 归一化后 [-0.25, 1.25]，两侧都出界。
        val box = assertNotNull(TargetTrackBox.of(areaOf(lengthX = 2880, lengthY = 1080)))
        assertNear(0f, box.left, "left")
        assertNear(0f, box.top, "top")
        assertNear(1f, box.width, "width")
        assertNear(1f, box.height, "height")
    }

    @Test
    fun `Auto —— 用声明过的模拟目标框，且是确定性常量`() {
        val state = TargetTrackState.of(TargetTrackMode.Auto, area = null, deviceId2 = null, atMs = 1_000L)
        assertEquals(TargetTrackState.SIMULATED_AUTO_BOX, state.box, "Auto 的设备目标由模拟器代答，必须是可复现的常量")
        assertTrue(state.isAuto)
        assertEquals(1_000L, state.startedAtMs)
    }

    @Test
    fun `Manual 无框选 —— box 为 null，绝不回落成模拟目标框`() {
        // 这条是本次实现里最要紧的一条：回落会把"平台没框选"在设备屏幕上画成
        // "平台框选了画面某个位置"，而目标跟踪**没有任何回执或查询**能证伪（9.3.1 d)）。
        val state = TargetTrackState.of(TargetTrackMode.Manual, area = null, deviceId2 = null, atMs = 2L)
        assertNull(state.box, "手动跟踪没有框就是没有框")
        assertTrue(!state.isAuto)
    }

    @Test
    fun `Manual 有框选 —— 用平台报文算出来的框，不是模拟框`() {
        val state = TargetTrackState.of(TargetTrackMode.Manual, area = areaOf(), deviceId2 = null, atMs = 3L)
        val box = assertNotNull(state.box)
        assertNear(0.5f, box.centerX, "centerX")
        assertTrue(box != TargetTrackState.SIMULATED_AUTO_BOX, "手动跟踪不得复用 Auto 的模拟框")
    }

    @Test
    fun `DeviceID2 —— 空白按「平台没带」处理，不回落成本机编码`() {
        val blank = TargetTrackState.of(TargetTrackMode.Auto, null, deviceId2 = "   ", atMs = 4L)
        assertNull(blank.deviceId2)

        val named = TargetTrackState.of(TargetTrackMode.Auto, null, deviceId2 = "34020000001320000009", atMs = 5L)
        assertEquals("34020000001320000009", named.deviceId2)
    }

    @Test
    fun `WHOLE_FRAME 与模拟目标框是两件事`() {
        // "没有目标"（整幅）与"假目标"（偏在一角）语义不同，别被合并掉 ——
        // 目前没有调用点表达"搜索中"，留着是为了别到时候随手编一个框。
        assertTrue(TargetTrackBox.WHOLE_FRAME != TargetTrackState.SIMULATED_AUTO_BOX)
        assertEquals(0f, TargetTrackBox.WHOLE_FRAME.left)
        assertEquals(1f, TargetTrackBox.WHOLE_FRAME.width)
    }
}

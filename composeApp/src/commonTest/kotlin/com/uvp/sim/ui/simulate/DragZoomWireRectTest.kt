package com.uvp.sim.ui.simulate

import com.uvp.sim.ui.model.DragZoomRectDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 拉框线框的**换算口径**（GB/T 28181-2022 A.2.3.1.8/.9）。
 *
 * ⛔ 标准原文的注：「命令中的坐标系以播放窗口的左上角原点，各坐标取值以像素单位」——
 * 所以分母必须是**报文里带来的 `Length` / `Width`**。2026-09-20 前这里写死
 * `size.width / 1000f`，等于假装"播放窗口恒为 1000px"：窗口更宽时线框直接画到画布外面，
 * 而 Compose 不会报错、肉眼只看得出"框好像偏了"。这一组用例就是钉住那件事。
 */
class DragZoomWireRectTest {

    private fun dto(
        midX: Int,
        midY: Int,
        lengthX: Int,
        lengthY: Int,
        frameLength: Int,
        frameWidth: Int,
    ) = DragZoomRectDto(midX, midY, lengthX, lengthY, frameLength, frameWidth)

    @Test
    fun `分母是报文的播放窗口尺寸 不是写死的 1000`() {
        // 播放窗口 2000x1000（比 1000 宽一倍），画布也是 2000x1000 ⇒ 像素级 1:1，
        // 框应当原样落在 (250,125) 200x100 处。
        // 旧实现按 /1000 缩放会得到 center=(1000,500) size=(800,400) —— 差一倍。
        val r = assertNotNull(
            dragZoomWireRect(dto(500, 250, 400, 200, 2000, 1000), canvasWidth = 2000f, canvasHeight = 1000f)
        )
        assertEquals(300f, r.left, 0.001f)
        assertEquals(150f, r.top, 0.001f)
        assertEquals(400f, r.width, 0.001f)
        assertEquals(200f, r.height, 0.001f)
    }

    @Test
    fun `横纵各用自己那把尺子`() {
        // ⛔ Length 是**播放窗口长度**（横向）、Width 是**宽度**（纵向）——两个名字都像"宽"，
        // 用反了不会有任何报错，只在非正方形窗口下横向或纵向错位。
        // 窗口 1000x500、画布 1000x500 ⇒ 归一化后 (0.25,0.25) 大小 (0.2,0.2) → 像素 (250,125) 200x100
        val r = assertNotNull(
            dragZoomWireRect(dto(250, 125, 200, 100, 1000, 500), canvasWidth = 1000f, canvasHeight = 500f)
        )
        assertEquals(150f, r.left, 0.001f)
        assertEquals(75f, r.top, 0.001f)
        assertEquals(200f, r.width, 0.001f)
        assertEquals(100f, r.height, 0.001f)
    }

    @Test
    fun `画布尺寸与播放窗口无关 只看比值`() {
        // 同一块画面，播放窗口大 4 倍、画布不变 ⇒ 线框位置必须一模一样
        // （这条成立，设备端才不需要知道"平台上那个播放窗口到底多大"）。
        val a = assertNotNull(
            dragZoomWireRect(dto(250, 125, 200, 100, 1000, 500), canvasWidth = 800f, canvasHeight = 400f)
        )
        val b = assertNotNull(
            dragZoomWireRect(dto(1000, 500, 800, 400, 4000, 2000), canvasWidth = 800f, canvasHeight = 400f)
        )
        assertEquals(a.left, b.left, 0.001f)
        assertEquals(a.top, b.top, 0.001f)
        assertEquals(a.width, b.width, 0.001f)
        assertEquals(a.height, b.height, 0.001f)
    }

    @Test
    fun `scale 只放大线框本身 中心不动`() {
        // 缓动放大（1 → 1.4×）必须绕中心长，不能把框挪走。
        val base = assertNotNull(
            dragZoomWireRect(dto(500, 250, 200, 100, 1000, 500), canvasWidth = 1000f, canvasHeight = 500f)
        )
        val grown = assertNotNull(
            dragZoomWireRect(dto(500, 250, 200, 100, 1000, 500), canvasWidth = 1000f, canvasHeight = 500f, scale = 1.4f)
        )
        assertEquals(400f, base.left, 0.001f)
        assertEquals(200f, base.width, 0.001f)
        assertEquals(280f, grown.width, 0.001f)
        assertEquals(base.left + base.width / 2f, grown.left + grown.width / 2f, 0.001f)
    }

    @Test
    fun `缺尺子 或退化尺寸 时画不出框`() {
        // 缺 Length/Width（畸形报文或老平台）⇒ null，而不是拿一个算不出归一化的框乱画。
        assertNull(dragZoomWireRect(dto(250, 125, 200, 100, 0, 0), 1000f, 500f))
        assertNull(dragZoomWireRect(dto(250, 125, 200, 100, 1000, 0), 1000f, 500f))
        // 退化框
        assertNull(dragZoomWireRect(dto(250, 125, 0, 100, 1000, 500), 1000f, 500f))
        // 画布还没量出尺寸（首帧）
        assertNull(dragZoomWireRect(dto(250, 125, 200, 100, 1000, 500), 0f, 500f))
    }
}

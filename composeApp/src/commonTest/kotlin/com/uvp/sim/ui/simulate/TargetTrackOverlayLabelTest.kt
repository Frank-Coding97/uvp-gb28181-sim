package com.uvp.sim.ui.simulate

import com.uvp.sim.ui.model.TargetTrackBoxDto
import com.uvp.sim.ui.model.TargetTrackDto
import com.uvp.sim.ui.model.TargetTrackModeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 目标跟踪角标的**文案口径**（GB/T 28181-2022 A.2.3.1.14）。
 *
 * ⛔ 这组用例盯的是一件事：**设备屏幕上写的字必须与"这条命令到底是谁说的"一致**。
 *
 * 目标跟踪是本标准里少见的"两难"命令：
 *   - 它是**无应答命令**（9.3.1 d)，表 1 序号 13 的应答栏是"（无）"）⇒ 平台没有回执；
 *   - 附录 A 也**没有**任何查询命令能把跟踪态读回去 ⇒ 平台连"设备到底在跟什么"都问不出来。
 *
 * 于是设备屏幕上那句话就是**唯一**的可见面。说错了没有任何东西能纠正：
 *   - `Manual` 的框是**平台报文里的真值**（`Length`/`Width` 两把尺子做的比值换算）；
 *   - `Auto` 的框是**模拟器编的**（设备 AI 没接真源，见 `TargetTrackState.SIMULATED_AUTO_BOX`），
 *     漏掉"模拟"二字，演示时就会被当成"设备真的自己找到目标了"。
 */
class TargetTrackOverlayLabelTest {

    private fun track(
        mode: TargetTrackModeDto,
        box: TargetTrackBoxDto? = TargetTrackBoxDto(0.34f, 0.28f, 0.30f, 0.40f),
    ) = TargetTrackDto(mode = mode, box = box, deviceId2 = null, startedAtMs = 1_000L)

    @Test
    fun `Auto 必须带「模拟目标」字样`() {
        val label = targetTrackOverlayLabel(track(TargetTrackModeDto.Auto))
        assertTrue(label.contains("自动"), "实际=$label")
        // ⛔ 这一条是本次实现里最要紧的防回归：去掉"模拟"就等于把假目标当成设备 AI 的输出，
        //    而这条命令平台收不到回执、也没有查询，没有任何东西能证伪。
        assertTrue(label.contains("模拟"), "自动跟踪的框是模拟器编的,角标必须自曝,实际=$label")
    }

    @Test
    fun `Manual 不得出现「模拟」字样`() {
        val label = targetTrackOverlayLabel(track(TargetTrackModeDto.Manual))
        assertEquals("目标跟踪 · 手动", label)
    }

    @Test
    fun `手动但手上没有框时要明说「无框选区域」`() {
        // 三种成因在设备侧不区分：报文没带 <TargetArea>（标准里它是 minOccurs=0，合法）/
        // 六个子元素不全 / 整框落在画面外。屏幕只需回答"现在有没有框"。
        val label = targetTrackOverlayLabel(track(TargetTrackModeDto.Manual, box = null))
        assertEquals("目标跟踪 · 手动 · 无框选区域", label)
    }

    @Test
    fun `Auto 的框不受 box 为空影响`() {
        // Auto 的框由模拟器提供，恒定存在 —— 这一条同时钉住"两种模式的框来源不同"。
        val label = targetTrackOverlayLabel(track(TargetTrackModeDto.Auto, box = null))
        assertTrue(label.contains("模拟"), "实际=$label")
    }
}

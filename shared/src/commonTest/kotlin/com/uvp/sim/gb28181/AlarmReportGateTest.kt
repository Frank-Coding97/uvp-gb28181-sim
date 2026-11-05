package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A.2.1.18 上报门控的**判定规则**（`AlarmReportConfig.allows`）。
 *
 * 真实报文拦截在 `ManscdpRouterTest` 里端到端验证；这里只钉住判定表本身 ——
 * 尤其是"`null` 一律放行"这条：写错会让平台关掉移动侦测之后**连手动报警都发不出去**，
 * 而那种故障在平台上表现为"设备离线"，排查方向会完全跑偏。
 */
class AlarmReportGateTest {

    private val allOn = AlarmReportState(motionDetection = 1, fieldDetection = 1)
    private val allOff = AlarmReportState(motionDetection = 0, fieldDetection = 0)

    @Test
    fun allOn_allowsEverything() {
        assertTrue(AlarmReportConfig.allows(allOn, null))
        assertTrue(AlarmReportConfig.allows(allOn, AlarmReportKind.MotionDetection))
        assertTrue(AlarmReportConfig.allows(allOn, AlarmReportKind.FieldDetection))
    }

    @Test
    fun allOff_stillAllowsGenericAlarm() {
        assertTrue(AlarmReportConfig.allows(allOff, null), "通用报警不受这两个开关约束")
        assertFalse(AlarmReportConfig.allows(allOff, AlarmReportKind.MotionDetection))
        assertFalse(AlarmReportConfig.allows(allOff, AlarmReportKind.FieldDetection))
    }

    @Test
    fun switchesAreIndependent() {
        val motionOnly = AlarmReportState(motionDetection = 1, fieldDetection = 0)
        assertTrue(AlarmReportConfig.allows(motionOnly, AlarmReportKind.MotionDetection))
        assertFalse(AlarmReportConfig.allows(motionOnly, AlarmReportKind.FieldDetection))

        val fieldOnly = AlarmReportState(motionDetection = 0, fieldDetection = 1)
        assertFalse(AlarmReportConfig.allows(fieldOnly, AlarmReportKind.MotionDetection))
        assertTrue(AlarmReportConfig.allows(fieldOnly, AlarmReportKind.FieldDetection))
    }

    /** 只认 `1`：脏值（历史落库的 2/3 之类）按关闭处理，绝不"非 0 即开"。 */
    @Test
    fun anyValueOtherThanOneCountsAsOff() {
        val dirty = AlarmReportState(motionDetection = 2, fieldDetection = -1)
        assertFalse(AlarmReportConfig.allows(dirty, AlarmReportKind.MotionDetection))
        assertFalse(AlarmReportConfig.allows(dirty, AlarmReportKind.FieldDetection))
    }

    /** 出厂默认必须与设备真实能力一致：移动侦测开、区域入侵关。 */
    @Test
    fun defaultMatchesDeviceBehaviour() {
        assertTrue(AlarmReportConfig.allows(AlarmReportConfig.DEFAULT, AlarmReportKind.MotionDetection))
        assertFalse(AlarmReportConfig.allows(AlarmReportConfig.DEFAULT, AlarmReportKind.FieldDetection))
    }
}

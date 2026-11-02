package com.uvp.sim.recording

import com.uvp.sim.gb28181.RecordSchedule
import com.uvp.sim.gb28181.RecordTimeSegment
import com.uvp.sim.gb28181.VideoRecordPlanState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [RecordPlanEvaluator] 的判定规则（A.2.1.15 录像计划 → 「现在该不该录」）。
 *
 * 调度器和录像计划页共用这一份判定，所以这里的边界就是**设备行为**的边界：
 * 这些用例过不了，就不能说"计划录像做对了"。
 */
class RecordPlanEvaluatorTest {

    // 2026-11-02 是周一 → weekday=1（对齐国标 WeekDayNum 1=周一）
    private fun now(weekday: Int = 1, hour: Int = 9, minute: Int = 0, second: Int = 0) =
        LocalClockReading(weekday = weekday, secondsOfDay = hour * 3600 + minute * 60 + second)

    private fun seg(sh: Int, sm: Int, ss: Int, eh: Int, em: Int, es: Int) =
        RecordTimeSegment(sh, sm, ss, eh, em, es)

    private fun plan(
        enable: Int = 1,
        vararg schedules: RecordSchedule,
        stream: Int = 0,
    ) = VideoRecordPlanState(recordEnable = enable, schedules = schedules.toList(), streamNumber = stream)

    private fun day(weekday: Int, vararg segments: RecordTimeSegment) =
        RecordSchedule(weekDayNum = weekday, segments = segments.toList())

    @Test
    fun activeSlot_hits_inside_segment() {
        val p = plan(1, day(1, seg(8, 0, 0, 12, 0, 0)))
        val slot = RecordPlanEvaluator.activeSlot(p, now(hour = 9))
        assertNotNull(slot)
        assertEquals("1:28800-43200", slot.key)
        assertEquals("周一 08:00:00-12:00:00", slot.label)
    }

    @Test
    fun activeSlot_is_left_closed_right_open() {
        val p = plan(1, day(1, seg(8, 0, 0, 12, 0, 0)))
        // 开始那一刻算进来了
        assertNotNull(RecordPlanEvaluator.activeSlot(p, now(hour = 8, second = 0)))
        // 结束那一刻不算 —— 否则相邻段（08-12 + 12-18）会在 12:00:00 同时命中，
        // 调度器在交界处反复 stop/start
        assertNull(RecordPlanEvaluator.activeSlot(p, now(hour = 12, second = 0)))
        assertNotNull(RecordPlanEvaluator.activeSlot(p, now(hour = 11, minute = 59, second = 59)))
    }

    @Test
    fun activeSlot_respects_weekday() {
        val p = plan(1, day(1, seg(8, 0, 0, 12, 0, 0)))
        assertNull(
            RecordPlanEvaluator.activeSlot(p, now(weekday = 2, hour = 9)),
            "周二没有计划，不能因为周一有就跟着录",
        )
    }

    @Test
    fun activeSlot_picks_second_segment_of_the_day() {
        // 一天多段是本仓刻意支持的口径（海康那种"一天一段"会静默丢段）
        val p = plan(
            1,
            day(1, seg(8, 0, 0, 12, 0, 0), seg(14, 0, 0, 18, 0, 0)),
        )
        val slot = RecordPlanEvaluator.activeSlot(p, now(hour = 15))
        assertNotNull(slot)
        assertEquals("周一 14:00:00-18:00:00", slot.label)
    }

    @Test
    fun activeSlot_returns_null_when_plan_disabled() {
        val p = plan(0, day(1, seg(8, 0, 0, 12, 0, 0)))
        assertNull(
            RecordPlanEvaluator.activeSlot(p, now(hour = 9)),
            "RecordEnable=0 时必须彻底停，不能因为计划还在就继续录",
        )
    }

    @Test
    fun activeSlot_skips_segments_whose_stop_is_not_after_start() {
        // ⛔ 不猜"跨零点"：22:00-06:00 这段在 23:00 也不该开录。
        // 真跨零点要拆成 22:00-23:59:59 + 00:00-06:00（页面有提示）。
        val p = plan(1, day(1, seg(22, 0, 0, 6, 0, 0)))
        assertNull(RecordPlanEvaluator.activeSlot(p, now(hour = 23)))
        // 零长段同理
        val zero = plan(1, day(1, seg(9, 0, 0, 9, 0, 0)))
        assertNull(RecordPlanEvaluator.activeSlot(zero, now(hour = 9)))
    }

    @Test
    fun inertSegmentCount_counts_non_executable_segments() {
        val p = plan(
            1,
            day(1, seg(22, 0, 0, 6, 0, 0), seg(8, 0, 0, 12, 0, 0)),
            day(2, seg(12, 0, 0, 12, 0, 0)),
        )
        assertEquals(2, RecordPlanEvaluator.inertSegmentCount(p))
    }

    @Test
    fun nextStart_inside_segment_finds_later_same_day_segment() {
        val p = plan(1, day(1, seg(8, 0, 0, 12, 0, 0), seg(14, 0, 0, 18, 0, 0)))
        val next = RecordPlanEvaluator.nextStart(p, now(hour = 9))
        assertNotNull(next)
        assertEquals(0, next.dayOffset)
        assertEquals(14 * 3600, next.secondsOfDay)
    }

    @Test
    fun nextStart_rolls_over_to_next_day() {
        // 只有周二有计划，今天是周一 → 明天 08:00
        val p = plan(1, day(2, seg(8, 0, 0, 12, 0, 0)))
        val next = RecordPlanEvaluator.nextStart(p, now(weekday = 1, hour = 20))
        assertNotNull(next)
        assertEquals(1, next.dayOffset)
        assertEquals(8 * 3600, next.secondsOfDay)
    }

    @Test
    fun nextStart_wraps_across_sunday_to_monday() {
        // 周日（7）看周一（1）的计划：偏移要按 7 天回绕，不是 1-7=-6
        val p = plan(1, day(1, seg(8, 0, 0, 12, 0, 0)))
        val next = RecordPlanEvaluator.nextStart(p, now(weekday = 7, hour = 20))
        assertNotNull(next)
        assertEquals(1, next.dayOffset)
        assertEquals(8 * 3600, next.secondsOfDay)
    }

    @Test
    fun nextStart_is_null_when_nothing_is_executable() {
        assertNull(RecordPlanEvaluator.nextStart(plan(1), now()))
        assertNull(
            RecordPlanEvaluator.nextStart(plan(0, day(1, seg(8, 0, 0, 12, 0, 0))), now()),
            "未启用的计划不该给出「下次会录」的暗示",
        )
        assertNull(
            RecordPlanEvaluator.nextStart(plan(1, day(1, seg(22, 0, 0, 6, 0, 0))), now()),
            "只有跨零点/零长段时，等同「没有可执行时段」",
        )
    }
}

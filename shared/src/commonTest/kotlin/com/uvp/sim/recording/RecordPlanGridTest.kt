package com.uvp.sim.recording

import com.uvp.sim.gb28181.RecordTimeSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RecordPlanGrid] 的格 ↔ 时段映射。
 *
 * 这些用例守的是「用户在网格上涂的东西，最终发给平台是不是同一件事」：
 * 合并漏了会发超限段数、末尾写成 24:00:00 会发非法报文、吸附方向反了会少吃时间。
 */
class RecordPlanGridTest {

    private fun seg(sh: Int, sm: Int, ss: Int, eh: Int, em: Int, es: Int) =
        RecordTimeSegment(sh, sm, ss, eh, em, es)

    /** 48 个布尔值里哪些下标为 true。 */
    private fun on(slots: BooleanArray): List<Int> = slots.indices.filter { slots[it] }

    @Test
    fun slotsOf_maps_half_hour_boundaries() {
        // 08:00-12:00 = 第 16~23 格（每格 30 分钟，08:00 是第 16 格起点）
        val slots = RecordPlanGrid.slotsOf(listOf(seg(8, 0, 0, 12, 0, 0)))
        assertEquals((16..23).toList(), on(slots))
    }

    @Test
    fun slotsOf_covers_whole_day_for_23_59_59() {
        val slots = RecordPlanGrid.slotsOf(listOf(seg(0, 0, 0, 23, 59, 59)))
        assertEquals((0..47).toList(), on(slots))
    }

    @Test
    fun segmentsOf_merges_adjacent_slots() {
        val slots = BooleanArray(RecordPlanGrid.SLOTS_PER_DAY)
        for (i in 16..23) slots[i] = true
        val segments = RecordPlanGrid.segmentsOf(slots)
        assertEquals(1, segments.size, "连续格子必须合并成一段，否则报文段数超限")
        assertEquals(seg(8, 0, 0, 12, 0, 0), segments.single())
    }

    @Test
    fun segmentsOf_splits_on_gap() {
        val slots = BooleanArray(RecordPlanGrid.SLOTS_PER_DAY)
        for (i in 16..19) slots[i] = true   // 08:00-10:00
        for (i in 24..27) slots[i] = true   // 12:00-14:00
        val segments = RecordPlanGrid.segmentsOf(slots)
        assertEquals(listOf(seg(8, 0, 0, 10, 0, 0), seg(12, 0, 0, 14, 0, 0)), segments)
    }

    @Test
    fun segmentsOf_full_day_stops_at_23_59_59() {
        val segments = RecordPlanGrid.segmentsOf(BooleanArray(RecordPlanGrid.SLOTS_PER_DAY) { true })
        assertEquals(seg(0, 0, 0, 23, 59, 59), segments.single())
        // ⛔ 绝不能出现 <Hour>24</Hour>：那是非法报文
        assertEquals(23, segments.single().stopHour)
        assertEquals(59, segments.single().stopMin)
        assertEquals(59, segments.single().stopSec)
    }

    @Test
    fun round_trip_on_grid_segment_is_stable() {
        val original = seg(20, 30, 0, 23, 30, 0)
        val slots = RecordPlanGrid.slotsOf(listOf(original))
        assertEquals(listOf(original), RecordPlanGrid.segmentsOf(slots))
    }

    @Test
    fun off_grid_segment_expands_outward() {
        // 08:07:30-12:33:15 ⇒ 向下取整到 08:00、向上取整到 13:00（宁多录不少录）
        val offGrid = seg(8, 7, 30, 12, 33, 15)
        assertFalse(RecordPlanGrid.isOnGrid(offGrid))
        val slots = RecordPlanGrid.slotsOf(listOf(offGrid))
        assertEquals((16..25).toList(), on(slots))
        assertEquals(listOf(seg(8, 0, 0, 13, 0, 0)), RecordPlanGrid.segmentsOf(slots))
    }

    @Test
    fun offGridCount_reports_only_inexpressible_segments() {
        val segments = listOf(
            seg(8, 0, 0, 12, 0, 0),        // 在界上
            seg(0, 0, 0, 23, 59, 59),      // 整天收尾，也算在界上
            seg(8, 7, 30, 12, 33, 15),     // 不在界上
        )
        assertEquals(1, RecordPlanGrid.offGridCount(segments))
    }

    @Test
    fun slotOfStop_rounds_up_and_caps_at_48() {
        assertEquals(24, RecordPlanGrid.slotOfStop(12 * 3600))
        assertEquals(25, RecordPlanGrid.slotOfStop(12 * 3600 + 1))
        assertEquals(48, RecordPlanGrid.slotOfStop(RecordPlanGrid.LAST_SECOND_OF_DAY))
    }

    @Test
    fun empty_slots_produce_no_segments() {
        assertEquals(emptyList(), RecordPlanGrid.segmentsOf(BooleanArray(RecordPlanGrid.SLOTS_PER_DAY)))
    }
}

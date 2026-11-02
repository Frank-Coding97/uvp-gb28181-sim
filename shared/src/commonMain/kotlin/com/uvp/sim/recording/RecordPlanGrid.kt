package com.uvp.sim.recording

import com.uvp.sim.gb28181.RecordTimeSegment

/**
 * 「周时间网格」（`timeGridWeek`）的格 ↔ 时段映射。
 *
 * 网格把一天切成固定粒度的格子（[SLOT_MINUTES]=30 分钟 ⇒ 一天 [SLOTS_PER_DAY]=48 格），
 * 用户在格子上点选/拖涂，落盘前必须还原成国标 `TimeSegment`（任意秒级起止）。
 * 这一层就是两个世界之间的翻译，纯函数、不碰 UI，便于单测。
 *
 * ## ⛔ 为什么必须有这一层
 *
 * 网格是**有损**的表达：它只能表示 30 分钟的整数倍。国标 `TimeSegment` 允许任意秒，
 * 平台完全可能下发 `08:07:30-12:33:15` —— 这种段在网格上画不出来，只能吸附成
 * `08:00-12:30`（用 [isOnGrid] 判定，UI 要据此提示"这是对齐后的近似值"）。
 *
 * 吸附方向选择**向外扩**（起点向下取整、终点向上取整）而不是四舍五入：
 * 录像宁可多录几十秒，也不能把用户/平台要的那几十秒裁掉。而多录的那点时间
 * 会体现在实际录像文件上，看得出来、可追溯。
 *
 * ## ⛔ 24:00:00 不存在
 *
 * 一天最后一格（23:30-24:00）的终点在国标里只能写 `23:59:59`，写 `24:00:00` 是非法报文
 * （`<Hour>24</Hour>` 超出 0~23）。所以 [segmentsOf] 对整天末尾做 [LAST_SECOND_OF_DAY] 收敛，
 * 而 [slotOfStop] 反过来把 `23:59:59` 认作一天结束（向上取整得 48），闭环成立。
 */
object RecordPlanGrid {

    /** 每格多少分钟。30 分钟是安防排程的通行粒度（海康 / 大华同口径）。 */
    const val SLOT_MINUTES: Int = 30

    /** 每格多少秒。 */
    const val SLOT_SECONDS: Int = SLOT_MINUTES * 60

    /** 一天多少格。 */
    const val SLOTS_PER_DAY: Int = 24 * 60 / SLOT_MINUTES

    /** 一天多少秒。 */
    const val SECONDS_PER_DAY: Int = 24 * 3600

    /** 一天最后一个合法的秒（`23:59:59`）。国标没有 `24:00:00`。 */
    const val LAST_SECOND_OF_DAY: Int = SECONDS_PER_DAY - 1

    /** 时段的起始秒。 */
    fun startSecondOf(segment: RecordTimeSegment): Int =
        segment.startHour * 3600 + segment.startMin * 60 + segment.startSec

    /** 时段的结束秒。 */
    fun stopSecondOf(segment: RecordTimeSegment): Int =
        segment.stopHour * 3600 + segment.stopMin * 60 + segment.stopSec

    /**
     * 起始秒落在第几格（向下取整）。
     *
     * 只用来标"从哪格开始涂"；`23:59:59` 这种末尾值给 [slotOfStop] 处理。
     */
    fun slotOfStart(secondOfDay: Int): Int =
        (secondOfDay.coerceIn(0, LAST_SECOND_OF_DAY) / SLOT_SECONDS).coerceIn(0, SLOTS_PER_DAY - 1)

    /**
     * 结束秒覆盖到第几格（向上取整，返回**开区间**上界，取值 1..[SLOTS_PER_DAY]）。
     *
     * `12:00:00` ⇒ 24（第 16~23 格被覆盖）；`12:33:15` ⇒ 26（补到 13:00）；
     * `23:59:59` ⇒ 48（整天末尾）。
     */
    fun slotOfStop(secondOfDay: Int): Int {
        val sec = secondOfDay.coerceIn(0, LAST_SECOND_OF_DAY)
        return ((sec + SLOT_SECONDS - 1) / SLOT_SECONDS).coerceIn(0, SLOTS_PER_DAY)
    }

    /** 一天的时段集合 → 48 格选中状态。 */
    fun slotsOf(segments: List<RecordTimeSegment>): BooleanArray {
        val slots = BooleanArray(SLOTS_PER_DAY)
        for (segment in segments) {
            val from = slotOfStart(startSecondOf(segment))
            val to = slotOfStop(stopSecondOf(segment))
            for (index in from until to) {
                if (index in slots.indices) slots[index] = true
            }
        }
        return slots
    }

    /**
     * 48 格选中状态 → 时段列表（相邻格子合并为一段）。
     *
     * ⛔ 必须合并：国标 `VideoRecordPlan` 一天最多 [com.uvp.sim.gb28181.VideoRecordPlanConfig.MAX_SEGMENTS_PER_DAY]
     * 段，用户横涂一片连续格子是**一段**而不是几十段。不合并会发出超限的非法报文，
     * 平台侧表现成"计划保存失败"却查不出原因。
     */
    fun segmentsOf(slots: BooleanArray): List<RecordTimeSegment> {
        val result = mutableListOf<RecordTimeSegment>()
        var index = 0
        while (index < slots.size) {
            if (!slots[index]) {
                index += 1
                continue
            }
            var end = index
            while (end + 1 < slots.size && slots[end + 1]) end += 1
            result += segmentOfSlots(index, end)
            index = end + 1
        }
        return result
    }

    /**
     * 第 [fromSlot]~[toSlotInclusive] 格（闭区间）对应的单个时段。
     *
     * 终点到 24:00 时收敛成 `23:59:59`，理由见类注释。
     */
    fun segmentOfSlots(fromSlot: Int, toSlotInclusive: Int): RecordTimeSegment {
        val from = fromSlot.coerceIn(0, SLOTS_PER_DAY - 1)
        val to = toSlotInclusive.coerceIn(from, SLOTS_PER_DAY - 1)
        val startSec = from * SLOT_SECONDS
        val stopSec = ((to + 1) * SLOT_SECONDS).coerceAtMost(LAST_SECOND_OF_DAY)
        return RecordTimeSegment(
            startHour = startSec / 3600,
            startMin = (startSec % 3600) / 60,
            startSec = startSec % 60,
            stopHour = stopSec / 3600,
            stopMin = (stopSec % 3600) / 60,
            stopSec = stopSec % 60,
        )
    }

    /**
     * 这个时段是否**正好**落在 30 分钟边界上（即在网格上可以被精确表达）。
     *
     * `23:59:59` 结尾视为在界上：它就是一整天的收尾，网格里对应最后一格结束。
     */
    fun isOnGrid(segment: RecordTimeSegment): Boolean {
        val start = startSecondOf(segment)
        val stop = stopSecondOf(segment)
        if (start % SLOT_SECONDS != 0) return false
        return stop == LAST_SECOND_OF_DAY || stop % SLOT_SECONDS == 0
    }

    /** 不在网格边界上的段数。> 0 时 UI 必须提示"网格是按对齐后的近似值显示的"。 */
    fun offGridCount(segments: List<RecordTimeSegment>): Int = segments.count { !isOnGrid(it) }

    /** 一天里被选中的格数（用来显示"共 N 小时"这类摘要）。 */
    fun selectedSlotCount(slots: BooleanArray): Int = slots.count { it }
}

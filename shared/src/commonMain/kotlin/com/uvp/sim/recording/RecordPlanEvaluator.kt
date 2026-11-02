package com.uvp.sim.recording

import com.uvp.sim.gb28181.RecordTimeSegment
import com.uvp.sim.gb28181.VideoRecordPlanState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 一次「设备本地时间」读数：星期几 + 当天第几秒。
 *
 * [weekday] 用**国标口径** 1~7 = 周一到周日，直接对齐
 * `RecordSchedule.WeekDayNum`，中间不再做一次换算是刻意的 —— 每多一层换算就多一处
 * 「周日算 0 还是 7」的错位机会。
 */
data class LocalClockReading(
    val weekday: Int,
    val secondsOfDay: Int,
)

/**
 * 设备本地墙钟（**不是** `ProtocolClock`）。
 *
 * ⭐ 两条时钟必须分开，理由不是洁癖：
 *   - `ProtocolClock` 是「协议对外时间」= 平台 Date 头基准 + 单调推进，它随平台校时漂移，
 *     而且平台时间可能被现场故意调偏（联调时很常见）。
 *   - 录像计划里的「08:00」是**设备本地一天里的 08:00**，跟手机上时钟一致才对得上现场
 *     的作息。拿协议时钟判断会出现「平台时间差 3 小时 → 计划在错的时间段录」。
 */
object LocalWallClock {

    fun now(): LocalClockReading {
        val dt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return LocalClockReading(
            weekday = dt.dayOfWeek.isoWeekdayNumber,
            secondsOfDay = dt.hour * 3600 + dt.minute * 60 + dt.second,
        )
    }
}

/**
 * `DayOfWeek` → 国标 1~7（周一~周日）。
 *
 * ⛔ 不写 `ordinal + 1`：那依赖枚举的**声明顺序**，一旦上游调整顺序就是静默错位 ——
 * 计划和录像都照跑，只是整体偏一天，现场极难看出来。显式映射写死一次，错不了。
 * （同 `RecordingFilter` 里那份 `isoDayNumber` 的口径。）
 */
private val DayOfWeek.isoWeekdayNumber: Int
    get() = when (this) {
        DayOfWeek.MONDAY -> 1
        DayOfWeek.TUESDAY -> 2
        DayOfWeek.WEDNESDAY -> 3
        DayOfWeek.THURSDAY -> 4
        DayOfWeek.FRIDAY -> 5
        DayOfWeek.SATURDAY -> 6
        DayOfWeek.SUNDAY -> 7
    }

/**
 * 命中一个计划时段。
 *
 * [key] 用于「本段被人工停止后不再自动拉起」的抑制判断 —— 段本身是可序列化数据类，
 * 直接当 map key 也行，但 key 字符串在日志里可读、也不会因为将来给段加字段而失效。
 */
data class ActiveRecordPlanSlot(
    val weekday: Int,
    val segment: RecordTimeSegment,
) {
    /** 该段在当天的起止（秒）。 */
    val startSeconds: Int get() = segment.startSecondOfDay
    val stopSeconds: Int get() = segment.stopSecondOfDay

    val key: String get() = "$weekday:$startSeconds-$stopSeconds"

    /** 人读串，日志用。 */
    val label: String get() = "${weekdayLabel(weekday)} ${segment.displayLabel}"
}

private fun weekdayLabel(weekDayNum: Int): String = when (weekDayNum) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "周?($weekDayNum)"
}

/** 「下一个要开始的时段」相对今天的天偏移 + 当天起始秒。 */
data class RecordPlanNextStart(
    /** 0 = 今天，1 = 明天……最多 6。 */
    val dayOffset: Int,
    val secondsOfDay: Int,
)

private val RecordTimeSegment.startSecondOfDay: Int
    get() = startHour * 3600 + startMin * 60 + startSec

private val RecordTimeSegment.stopSecondOfDay: Int
    get() = stopHour * 3600 + stopMin * 60 + stopSec

/**
 * 把「一份录像计划 + 一个时刻」判成「现在该不该录」。
 *
 * 纯函数，不碰时钟也不碰录像服务 —— 调度器（[RecordPlanScheduler]）与页面
 * （`VideoRecordPlanScreen` 的运行状态卡）用的是**同一个判定**，否则会出现
 * 「页面说正在录、实际没录」这类两侧各算一遍才会有的错位。
 *
 * ## 判定规则
 *
 *  - `RecordEnable != 1` → 任何时刻都不录（计划关掉就该彻底停，不保留"上次还在录"）
 *  - 只认 `WeekDayNum == 今天` 那一条；标准里一周最多 7 条、按天独立
 *  - 区间是**左闭右开** `[start, stop)`：`08:00:00-12:00:00` 在 12:00:00 整点结束，
 *    不把 12:00:00 这一秒算进本段。闭区间会让「上一段结束 = 下一段开始」的相邻段在
 *    交界那一秒同时命中，进而让调度器在交界处反复 stop/start。
 *  - `stop <= start` 的段**不参与调度**（见下方 ⚠️）
 *
 * ## ⚠️ 为什么 `stop <= start` 的段直接跳过，而不是当"跨零点"
 *
 * 标准没有规定 `Stop < Start` 是跨零点还是非法，本仓在**配置层**的既定口径是
 * 「照实记账、照实回读，不替平台解释」（见 `VideoRecordPlanConfig`）。到了调度层
 * 必须二选一，这时选"跳过"而不是"跨零点"，因为：
 *   1. 猜成跨零点会让设备录出一段**平台从没要求过**的录像（例如把误填的
 *      `08:00-00:00` 当成 8 小时长录）；
 *   2. 真跨零点在国标设备上的通行做法是拆成`22:00-23:59:59` + `00:00-06:00` 两段，
 *      本页支持一天 8 段，拆得下。
 *
 * 跳过时由调用方（调度器）打一条日志，让运维知道"这段没有生效，不是设备漏录"。
 */
object RecordPlanEvaluator {

    /** 当前时刻命中的时段；不该录（或计划未启用）返回 null。 */
    fun activeSlot(plan: VideoRecordPlanState, now: LocalClockReading): ActiveRecordPlanSlot? {
        if (plan.recordEnable != ENABLED) return null
        val schedule = plan.schedules.firstOrNull { it.weekDayNum == now.weekday } ?: return null
        for (segment in schedule.segments) {
            if (segment.stopSecondOfDay <= segment.startSecondOfDay) continue
            if (now.secondsOfDay >= segment.startSecondOfDay && now.secondsOfDay < segment.stopSecondOfDay) {
                return ActiveRecordPlanSlot(weekday = now.weekday, segment = segment)
            }
        }
        return null
    }

    /**
     * 下一个 **start 时刻**（不含"现在正在录"的当前段）。
     *
     * UI 用它渲染「下次 18:00 自动开始」；正在录时不该显示这个，而应显示当前段的结束时间
     * （[activeSlot] 拿到的 `segment.displayLabel` 后半段）。
     *
     * 最多往后看 7 天：一周都没有任何可执行段就返回 null（= 这份计划永远不会录）。
     */
    fun nextStart(plan: VideoRecordPlanState, now: LocalClockReading): RecordPlanNextStart? {
        if (plan.recordEnable != ENABLED) return null
        for (offset in 0..6) {
            val weekday = ((now.weekday - 1 + offset) % 7) + 1
            val schedule = plan.schedules.firstOrNull { it.weekDayNum == weekday } ?: continue
            val lowerBound = if (offset == 0) now.secondsOfDay else -1
            val candidate = schedule.segments
                .filter { it.stopSecondOfDay > it.startSecondOfDay }
                .map { it.startSecondOfDay }
                .filter { it > lowerBound }
                .minOrNull()
                ?: continue
            return RecordPlanNextStart(dayOffset = offset, secondsOfDay = candidate)
        }
        return null
    }

    /**
     * 这份计划里有多少个「永远不会生效」的段（`stop <= start`）。
     *
     * 页面拿它提示用户，别让老板以为设备漏录 —— 见上面类注释的说明。
     */
    fun inertSegmentCount(plan: VideoRecordPlanState): Int =
        plan.schedules.sumOf { schedule ->
            schedule.segments.count { it.stopSecondOfDay <= it.startSecondOfDay }
        }

    /** `RecordEnable` 的"启用"取值（标准 0-否 / 1-是）。 */
    private const val ENABLED = 1
}

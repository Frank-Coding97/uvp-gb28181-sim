package com.uvp.sim.recording

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * 列表筛选器:时间区间 + 通道关键字。
 *
 * apply 顺序:
 *   1. 时间区间重叠(同 RecordingIndex.queryByTimeRange 规则)
 *   2. channelKeyword 大小写不敏感,空串透传
 */
data class RecordingFilter(
    val startMs: Long,
    val endMs: Long,
    val channelKeyword: String = ""
) {
    fun apply(files: List<RecordingFile>): List<RecordingFile> {
        val keyword = channelKeyword.trim()
        return files.asSequence()
            .filter { startMs <= it.endTimeMs && endMs >= it.startTimeMs }
            .filter {
                keyword.isEmpty() ||
                    it.channelId.contains(keyword, ignoreCase = true)
            }
            .sortedBy { it.startTimeMs }
            .toList()
    }

    object Defaults {
        /**
         * 本周(以系统时区周一为周首,周日为周尾,左闭右闭天粒度)。
         * 例:2026-06-12(周五)+0800 → [2026-06-08 00:00, 2026-06-14 23:59:59.999]
         */
        fun thisWeek(now: Instant, tz: TimeZone = TimeZone.currentSystemDefault()): RecordingFilter {
            val today: LocalDate = now.toLocalDateTime(tz).date
            val daysFromMonday = (today.dayOfWeek.isoDayNumber - 1).toLong()
            val monday = today.minusDays(daysFromMonday)
            val sunday = monday.plusDays(6)
            val startMs = monday.atStartOfDayIn(tz).toEpochMilliseconds()
            // 周日 23:59:59.999 = 下周一 00:00 - 1ms
            val endMs = sunday.plusDays(1).atStartOfDayIn(tz).toEpochMilliseconds() - 1
            return RecordingFilter(startMs = startMs, endMs = endMs)
        }
    }
}

private val DayOfWeek.isoDayNumber: Int
    get() = when (this) {
        DayOfWeek.MONDAY -> 1
        DayOfWeek.TUESDAY -> 2
        DayOfWeek.WEDNESDAY -> 3
        DayOfWeek.THURSDAY -> 4
        DayOfWeek.FRIDAY -> 5
        DayOfWeek.SATURDAY -> 6
        DayOfWeek.SUNDAY -> 7
        else -> 1
    }

private fun LocalDate.minusDays(days: Long): LocalDate =
    plusDays(-days)

private fun LocalDate.plusDays(days: Long): LocalDate {
    if (days == 0L) return this
    val epochDay = this.toEpochDays() + days
    return LocalDate.fromEpochDays(epochDay.toInt())
}

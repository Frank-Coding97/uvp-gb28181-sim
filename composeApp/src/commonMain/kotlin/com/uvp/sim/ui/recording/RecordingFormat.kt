package com.uvp.sim.ui.recording

import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.ui.model.RecordSourceDto
import com.uvp.sim.ui.model.RecordingFileDto
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 录像 tab 共享的格式化 / 分组 / 筛选辅助函数.
 * 全部 KMP-friendly,无平台依赖.
 */

internal fun groupByDate(
    files: List<RecordingFileDto>,
    tz: TimeZone
): List<Pair<String, List<RecordingFileDto>>> {
    val sorted = files.sortedByDescending { it.startTimeMs }
    val result = linkedMapOf<String, MutableList<RecordingFileDto>>()
    for (f in sorted) {
        val date = Instant.fromEpochMilliseconds(f.startTimeMs).toLocalDateTime(tz).date
        val key = formatDate(date)
        result.getOrPut(key) { mutableListOf() }.add(f)
    }
    return result.map { (k, v) -> k to v.toList() }
}

internal fun formatDate(date: LocalDate): String =
    "${date.year}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"

internal fun formatTime(epochMs: Long): String {
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}:${ldt.second.toString().padStart(2, '0')}"
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "${h}h${m.toString().padStart(2, '0')}m${s.toString().padStart(2, '0')}s"
    } else {
        "${m}m${s.toString().padStart(2, '0')}s"
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
    else -> "${bytes / 1024 / 1024 / 1024} GB"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1fGB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.0fMB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

internal fun formatDurationShort(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}h${m}m" else if (m > 0) "${m}m${s}s" else "${s}s"
}

internal fun describeFilter(filter: RecordingFilter, tz: TimeZone): String {
    val s = Instant.fromEpochMilliseconds(filter.startMs).toLocalDateTime(tz).date
    val e = Instant.fromEpochMilliseconds(filter.endMs).toLocalDateTime(tz).date
    return if (s == e) formatDate(s) else "${formatDate(s)} ~ ${formatDate(e)}"
}

internal fun RecordSourceDto.label(): String = when (this) {
    RecordSourceDto.Manual -> "手动"
    RecordSourceDto.PlatformCmd -> "平台"
}

internal fun RecordingFilter.applyToDto(files: List<RecordingFileDto>): List<RecordingFileDto> {
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

/** 闰年规则:能被 4 整除且不能被 100 整除,或能被 400 整除。 */
internal fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    else -> 30
}

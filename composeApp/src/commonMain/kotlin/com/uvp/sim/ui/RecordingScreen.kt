package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingFilter
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * M2 录像 tab。
 *
 * - 列表按日期(YYYY-MM-DD)倒序分组
 * - 卡片含缩略图 / 起止时间区间 / 时长 / 大小 / 来源 / 删除按钮
 * - 点击卡片 → 内嵌 ExoPlayer Dialog
 * - 顶部筛选条:本周(默认) / 自定义日期范围 → ModalBottomSheet 含 DateRangePicker
 * - 删除走二次确认 + actions.onRecordingDelete
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(state: AppUiState, actions: AppActions, modifier: Modifier = Modifier) {
    val tz = remember { TimeZone.currentSystemDefault() }
    var playingFile by remember { mutableStateOf<RecordingFile?>(null) }
    var deletingFile by remember { mutableStateOf<RecordingFile?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    // 默认空筛选(显全量),老板按"本周"按钮才生效
    var filter by remember { mutableStateOf<RecordingFilter?>(null) }

    val files = state.recording.files
    val filtered = remember(files, filter) {
        val f = filter ?: return@remember files
        f.apply(files)
    }
    val grouped = remember(filtered) { groupByDate(filtered, tz) }
    val filterLabel = filter?.let { describeFilter(it, tz) } ?: "全部"

    Column(
        modifier = modifier.fillMaxSize().background(UvpColor.Bg)
    ) {
        FilterBar(
            filterLabel = filterLabel,
            hasFilter = filter != null,
            onClickFilter = { showFilterSheet = true },
            onClickThisWeek = {
                filter = RecordingFilter.Defaults.thisWeek(Clock.System.now(), tz)
            },
            onClear = { filter = null }
        )
        if (filtered.isNotEmpty()) {
            SummaryBar(filtered)
        }
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyHint(
                    title = if (files.isEmpty()) "尚无录像" else "当前筛选无录像",
                    subtitle = if (files.isEmpty()) "在主屏点「录像」开始" else "试试改下时间范围"
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp
                )
            ) {
                grouped.forEach { (date, list) ->
                    item(key = "h-$date") { DateHeader(date) }
                    items(list, key = { it.id }) { file ->
                        RecordingCard(
                            file = file,
                            onClick = { playingFile = file },
                            onDelete = { deletingFile = file }
                        )
                    }
                    item(key = "s-$date") { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    // 内嵌播放器
    RecordingPlayerDialog(
        filePath = playingFile?.filePath,
        onDismiss = { playingFile = null }
    )

    // 删除二次确认
    val target = deletingFile
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deletingFile = null },
            title = { Text("删除录像?") },
            text = {
                Text(
                    "${formatTime(target.startTimeMs)} 这段录像将被永久删除\n(文件 ${formatSize(target.sizeBytes)} + 缩略图)",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onRecordingDelete(target.id)
                    deletingFile = null
                }) { Text("删除", color = UvpColor.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deletingFile = null }) { Text("取消") }
            }
        )
    }

    // 日期筛选 sheet
    if (showFilterSheet) {
        FilterSheet(
            initial = filter,
            tz = tz,
            allFiles = files,
            onApply = { newFilter ->
                filter = newFilter
                showFilterSheet = false
            },
            onReset = {
                filter = null
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false }
        )
    }
}

@Composable
private fun FilterBar(
    filterLabel: String,
    hasFilter: Boolean,
    onClickFilter: () -> Unit,
    onClickThisWeek: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.Border, RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .background(UvpColor.PrimaryLight, RoundedCornerShape(8.dp))
                .clickable { onClickFilter() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Outlined.FilterList, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = UvpColor.Primary
                )
                Text("筛选", color = UvpColor.Primary, fontSize = 13.sp)
            }
        }
        Box(
            modifier = Modifier
                .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
                .clickable { onClickThisWeek() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("本周", color = UvpColor.TextSecondary, fontSize = 12.sp)
        }
        Box(modifier = Modifier.weight(1f))
        Text(
            filterLabel,
            color = if (hasFilter) UvpColor.Primary else UvpColor.TextHint,
            fontSize = 11.sp,
            fontWeight = if (hasFilter) FontWeight.Medium else FontWeight.Normal
        )
        if (hasFilter) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .clickable { onClear() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text("清除", color = UvpColor.Danger, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SummaryBar(files: List<RecordingFile>) {
    val totalBytes = files.sumOf { it.sizeBytes }
    val totalDurationMs = files.sumOf { it.durationMs }
    val sizeText = formatBytes(totalBytes)
    val durationText = formatDurationShort(totalDurationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Bg)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${files.size} 段", color = UvpColor.TextSecondary, fontSize = 11.sp)
        Text("·", color = UvpColor.TextHint, fontSize = 11.sp)
        Text(sizeText, color = UvpColor.TextSecondary, fontSize = 11.sp)
        Text("·", color = UvpColor.TextHint, fontSize = 11.sp)
        Text(durationText, color = UvpColor.TextSecondary, fontSize = 11.sp)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1fGB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.0fMB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

private fun formatDurationShort(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}h${m}m" else if (m > 0) "${m}m${s}s" else "${s}s"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    initial: RecordingFilter?,
    tz: TimeZone,
    allFiles: List<RecordingFile>,
    onApply: (RecordingFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val nowMs = remember { Clock.System.now().toEpochMilliseconds() }
    val initialDate = remember {
        Instant.fromEpochMilliseconds(initial?.startMs ?: nowMs)
            .toLocalDateTime(tz).date
    }
    val today = remember { Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date }
    var viewYear by remember { mutableStateOf(initialDate.year) }
    var viewMonth by remember { mutableStateOf(initialDate.monthNumber) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(initialDate) }
    // 哪些日期有录像 — 提前归并成 Set 让日历 O(1) 查
    val datesWithFiles = remember(allFiles) {
        allFiles.map {
            Instant.fromEpochMilliseconds(it.startTimeMs).toLocalDateTime(tz).date
        }.toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(8.dp),
        title = {
            Column {
                Text("按日期筛选", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = UvpColor.Text)
                Text(
                    "选择某一天,只显示当天的录像",
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        },
        text = {
            HardCalendar(
                viewYear = viewYear,
                viewMonth = viewMonth,
                today = today,
                selected = selectedDate,
                datesWithFiles = datesWithFiles,
                onPrevMonth = {
                    if (viewMonth == 1) { viewYear -= 1; viewMonth = 12 } else viewMonth -= 1
                },
                onNextMonth = {
                    if (viewMonth == 12) { viewYear += 1; viewMonth = 1 } else viewMonth += 1
                },
                onSelect = { selectedDate = it }
            )
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    val d = selectedDate ?: return@Button
                    val startMs = d.atStartOfDayIn(tz).toEpochMilliseconds()
                    val endMs = LocalDate.fromEpochDays(d.toEpochDays() + 1)
                        .atStartOfDayIn(tz).toEpochMilliseconds() - 1
                    onApply(RecordingFilter(startMs = startMs, endMs = endMs))
                },
                shape = RoundedCornerShape(6.dp)
            ) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("重置", color = UvpColor.TextSecondary) }
        }
    )
}

/**
 * 硬朗风格日历 — 方格 cell + 直角边 + 等宽 7 列。
 *
 * 替代 Material3 DatePicker(圆胶囊太软,且在 AlertDialog 里宽度撑不开)。
 * cell 之所以全部走 Modifier.weight(1f) 是为了让 7 列自动等分对话框可用宽度,
 * 避免最右边那列被父容器边距挡掉。
 */
@Composable
private fun HardCalendar(
    viewYear: Int,
    viewMonth: Int,
    today: LocalDate,
    selected: LocalDate?,
    datesWithFiles: Set<LocalDate>,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    val daysInMonth = daysInMonth(viewYear, viewMonth)
    // 月初是周几(0=日, 1=一, ..., 6=六)— DayOfWeek isoNumber 是 1=一..7=日,转一下
    val firstDay = LocalDate(viewYear, viewMonth, 1)
    val firstDayIsoNum = firstDay.dayOfWeek.ordinal + 1  // ISO: 1=Mon..7=Sun
    val firstDayOfWeekSun0 = firstDayIsoNum % 7  // 7=日 → 0
    val weeks = ((firstDayOfWeekSun0 + daysInMonth + 6) / 7).coerceAtLeast(1)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 月份切换头
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clickable { onPrevMonth() }
                    .padding(8.dp)
            ) { Text("◀", fontSize = 14.sp, color = UvpColor.TextSecondary) }
            Spacer(Modifier.width(8.dp))
            Text(
                "${viewYear}年${viewMonth}月",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = UvpColor.Text,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clickable { onNextMonth() }
                    .padding(8.dp)
            ) { Text("▶", fontSize = 14.sp, color = UvpColor.TextSecondary) }
        }
        // 周表头:日 一 二 三 四 五 六
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { d ->
                Text(
                    d,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 11.sp,
                    color = UvpColor.TextHint,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        // 6 行格子(部分月份只用 5 行,空格用空 Box 占位)
        var dayCounter = 1
        repeat(weeks) { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                repeat(7) { col ->
                    val cellIndex = row * 7 + col
                    val dayNum = cellIndex - firstDayOfWeekSun0 + 1
                    val isInMonth = dayNum in 1..daysInMonth
                    if (isInMonth) {
                        val date = LocalDate(viewYear, viewMonth, dayNum)
                        val isSelected = date == selected
                        val isToday = date == today
                        val hasFile = date in datesWithFiles
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .padding(1.dp)
                                .background(
                                    when {
                                        isSelected -> UvpColor.Primary
                                        isToday -> UvpColor.PrimaryLight
                                        else -> UvpColor.Surface
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) UvpColor.PrimaryDark else UvpColor.Border
                                )
                                .clickable { onSelect(date) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "$dayNum",
                                    fontSize = 13.sp,
                                    color = when {
                                        isSelected -> androidx.compose.ui.graphics.Color.White
                                        isToday -> UvpColor.Primary
                                        else -> UvpColor.Text
                                    },
                                    fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal
                                )
                                // 录像存在标点 — 选中时白点(蓝底),其他时候用 Danger 红点
                                if (hasFile) {
                                    Spacer(Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(
                                                if (isSelected) androidx.compose.ui.graphics.Color.White
                                                else UvpColor.Danger
                                            )
                                    )
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f).height(36.dp))
                    }
                    dayCounter += if (isInMonth) 1 else 0
                }
            }
        }
    }
}

/** 闰年规则:能被 4 整除且不能被 100 整除,或能被 400 整除。 */
private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    else -> 30
}

@Composable
private fun DateHeader(date: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    ) {
        Text(
            date,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = UvpColor.TextSecondary
        )
    }
}

@Composable
private fun RecordingCard(
    file: RecordingFile,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ThumbnailBox(file.thumbnailPath)
        Column(modifier = Modifier.weight(1f)) {
            // 起止时间区间:09:56:53 → 09:57:13
            Text(
                "${formatTime(file.startTimeMs)} → ${formatTime(file.endTimeMs)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = UvpColor.Text
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    formatDuration(file.durationMs),
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary
                )
                Text("·", fontSize = 11.sp, color = UvpColor.TextHint)
                Text(
                    formatSize(file.sizeBytes),
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary
                )
                Text("·", fontSize = 11.sp, color = UvpColor.TextHint)
                Text(
                    file.source.label(),
                    fontSize = 10.sp,
                    color = UvpColor.TextHint
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除",
                tint = UvpColor.Danger,
                modifier = Modifier.size(18.dp)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ThumbnailBox(path: String?) {
    Box(
        modifier = Modifier
            .width(80.dp)
            .height(45.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(UvpColor.BorderLight),
        contentAlignment = Alignment.Center
    ) {
        RecordingThumbnail(
            filePath = path,
            modifier = Modifier.fillMaxSize(),
            onMissing = {
                Icon(
                    Icons.Outlined.Movie, contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = UvpColor.TextHint
                )
            }
        )
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(UvpColor.PrimaryLight, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Videocam, contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = UvpColor.Primary
            )
        }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = UvpColor.Text)
        Text(subtitle, fontSize = 11.sp, color = UvpColor.TextSecondary)
    }
}

private fun groupByDate(files: List<RecordingFile>, tz: TimeZone): List<Pair<String, List<RecordingFile>>> {
    val sorted = files.sortedByDescending { it.startTimeMs }
    val result = linkedMapOf<String, MutableList<RecordingFile>>()
    for (f in sorted) {
        val date = Instant.fromEpochMilliseconds(f.startTimeMs).toLocalDateTime(tz).date
        val key = formatDate(date)
        result.getOrPut(key) { mutableListOf() }.add(f)
    }
    return result.map { (k, v) -> k to v.toList() }
}

private fun formatDate(date: LocalDate): String =
    "${date.year}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"

private fun formatTime(epochMs: Long): String {
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}:${ldt.second.toString().padStart(2, '0')}"
}

private fun formatDuration(ms: Long): String {
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

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
    else -> "${bytes / 1024 / 1024 / 1024} GB"
}

private fun describeFilter(filter: RecordingFilter, tz: TimeZone): String {
    val s = Instant.fromEpochMilliseconds(filter.startMs).toLocalDateTime(tz).date
    val e = Instant.fromEpochMilliseconds(filter.endMs).toLocalDateTime(tz).date
    return if (s == e) formatDate(s) else "${formatDate(s)} ~ ${formatDate(e)}"
}

/**
 * Material3 [DateRangePicker] 返回的 epoch ms 是该日期的 UTC 0 点;
 * 但语义上"6 月 12 日"应该指**本地时区**那一整天。
 * 所以转一下:取 UTC ms → LocalDate(UTC),再用本地时区 atStartOfDayIn 取真正的本地 0 点。
 */
private fun utcDayStartToLocalDayStart(utcMs: Long, tz: TimeZone): Long {
    val date = Instant.fromEpochMilliseconds(utcMs).toLocalDateTime(TimeZone.UTC).date
    return date.atStartOfDayIn(tz).toEpochMilliseconds()
}

private fun utcDayStartToLocalDayEnd(utcMs: Long, tz: TimeZone): Long {
    val date = Instant.fromEpochMilliseconds(utcMs).toLocalDateTime(TimeZone.UTC).date
    val nextDay = LocalDate.fromEpochDays(date.toEpochDays() + 1)
    return nextDay.atStartOfDayIn(tz).toEpochMilliseconds() - 1
}

private fun com.uvp.sim.recording.RecordSource.label(): String = when (this) {
    com.uvp.sim.recording.RecordSource.Manual -> "手动"
    com.uvp.sim.recording.RecordSource.PlatformCmd -> "平台"
}

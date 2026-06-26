package com.uvp.sim.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.RecordingFileDto
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * 按日期筛选录像 — AlertDialog + 自绘日历(HardCalendar).
 * Material3 DatePicker 在 dialog 里宽度撑不开,改用方格 cell + 直角边.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterSheet(
    initial: RecordingFilter?,
    tz: TimeZone,
    allFiles: List<RecordingFileDto>,
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
            Button(
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
 * 硬朗风格日历 — 方格 cell + 直角边 + 等宽 7 列.
 *
 * 替代 Material3 DatePicker(圆胶囊太软,且在 AlertDialog 里宽度撑不开).
 * cell 之所以全部走 Modifier.weight(1f) 是为了让 7 列自动等分对话框可用宽度,
 * 避免最右边那列被父容器边距挡掉.
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
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    color = UvpColor.TextHint,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        // 6 行格子(部分月份只用 5 行,空格用空 Box 占位)
        repeat(weeks) { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                repeat(7) { col ->
                    val cellIndex = row * 7 + col
                    val dayNum = cellIndex - firstDayOfWeekSun0 + 1
                    val isInMonth = dayNum in 1..daysInMonth
                    if (isInMonth) {
                        val date = LocalDate(viewYear, viewMonth, dayNum)
                        CalendarDayCell(
                            date = date,
                            dayNum = dayNum,
                            isSelected = date == selected,
                            isToday = date == today,
                            hasFile = date in datesWithFiles,
                            onClick = { onSelect(date) },
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).height(36.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CalendarDayCell(
    date: LocalDate,
    dayNum: Int,
    isSelected: Boolean,
    isToday: Boolean,
    hasFile: Boolean,
    onClick: () -> Unit,
) {
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
            .clickable { onClick() },
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
                    isSelected -> Color.White
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
                        .clip(CircleShape)
                        .background(
                            if (isSelected) Color.White
                            else UvpColor.Danger
                        )
                )
            }
        }
    }
}

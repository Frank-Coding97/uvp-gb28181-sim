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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingFilter
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * M2 录像 tab(plan §8.2)。
 *
 * 列表按日期(YYYYMMDD)倒序分组,卡片含缩略图 / 时间 / 时长 / 大小。
 * 顶部筛选条:`[筛选 ▾] [本周]`。空态显示"尚无录像"提示。
 *
 * 长按菜单 / 系统播放器调起在 R07 接入(本文件留 hook)。
 */
@Composable
fun RecordingScreen(state: AppUiState, actions: AppActions) {
    val tz = remember { TimeZone.currentSystemDefault() }
    var selectedFile by remember { mutableStateOf<RecordingFile?>(null) }

    val files = state.recording.files
    if (files.isEmpty()) {
        EmptyState()
        return
    }

    val grouped = remember(files) { groupByDate(files, tz) }

    Column(
        modifier = Modifier.fillMaxSize().background(UvpColor.Bg)
    ) {
        FilterBar(filterLabel = "本周", onClickFilter = { /* R07 接入 */ })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp
            )
        ) {
            grouped.forEach { (date, list) ->
                item(key = "h-$date") {
                    DateHeader(date)
                }
                items(list, key = { it.id }) { file ->
                    RecordingCard(file = file, onClick = { selectedFile = file })
                }
                item(key = "s-$date") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun FilterBar(filterLabel: String, onClickFilter: () -> Unit) {
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
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(filterLabel, color = UvpColor.TextSecondary, fontSize = 12.sp)
        }
    }
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
private fun RecordingCard(file: RecordingFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ThumbnailBox(file.thumbnailPath)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                formatTime(file.startTimeMs),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = UvpColor.Text
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatDuration(file.durationMs)}  ·  ${formatSize(file.sizeBytes)}",
                fontSize = 11.sp,
                color = UvpColor.TextSecondary
            )
        }
        Text(
            file.source.label(),
            fontSize = 10.sp,
            color = UvpColor.TextHint
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ThumbnailBox(path: String?) {
    Box(
        modifier = Modifier
            .width(80.dp)
            .height(45.dp)
            .background(UvpColor.BorderLight, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        // 真实缩略图加载在 R07 接 painter,这里先用 icon 占位避免 commonMain 直接读 File。
        Icon(
            Icons.Outlined.Movie, contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = UvpColor.TextHint
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp).background(UvpColor.Bg),
        contentAlignment = Alignment.Center
    ) {
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
            Text(
                "尚无录像",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text
            )
            Text(
                "在主屏点「录像」开始",
                fontSize = 11.sp,
                color = UvpColor.TextSecondary
            )
        }
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

private fun com.uvp.sim.recording.RecordSource.label(): String = when (this) {
    com.uvp.sim.recording.RecordSource.Manual -> "手动"
    com.uvp.sim.recording.RecordSource.PlatformCmd -> "平台"
}

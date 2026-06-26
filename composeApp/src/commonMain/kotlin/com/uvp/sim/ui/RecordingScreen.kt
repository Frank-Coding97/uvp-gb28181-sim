package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.ui.model.RecordingFileDto
import com.uvp.sim.ui.recording.DateHeader
import com.uvp.sim.ui.recording.EmptyHint
import com.uvp.sim.ui.recording.FilterBar
import com.uvp.sim.ui.recording.FilterSheet
import com.uvp.sim.ui.recording.RecordingCard
import com.uvp.sim.ui.recording.SummaryBar
import com.uvp.sim.ui.recording.applyToDto
import com.uvp.sim.ui.recording.describeFilter
import com.uvp.sim.ui.recording.formatSize
import com.uvp.sim.ui.recording.formatTime
import com.uvp.sim.ui.recording.groupByDate
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * M2 录像 tab。
 *
 * - 列表按日期(YYYY-MM-DD)倒序分组
 * - 卡片含缩略图 / 起止时间区间 / 时长 / 大小 / 来源 / 删除按钮
 * - 点击卡片 → 内嵌 ExoPlayer Dialog
 * - 顶部筛选条:本周(默认) / 自定义日期范围 → ModalBottomSheet 含 DateRangePicker
 * - 删除走二次确认 + actions.onRecordingDelete
 *
 * 2026-06-26 PR-F T3:
 *   - 筛选条 / SummaryBar / 日期筛选弹窗 / 卡片 / DateHeader / EmptyHint / 格式化工具
 *     全部下沉到 com.uvp.sim.ui.recording 子包,本文件只剩主入口编排.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(state: AppUiState, actions: AppActions, modifier: Modifier = Modifier) {
    val tz = remember { TimeZone.currentSystemDefault() }
    var playingFile by remember { mutableStateOf<RecordingFileDto?>(null) }
    var deletingFile by remember { mutableStateOf<RecordingFileDto?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    // 默认空筛选(显全量),老板按"本周"按钮才生效
    var filter by remember { mutableStateOf<RecordingFilter?>(null) }

    val files = state.recording.files
    val filtered = remember(files, filter) {
        val f = filter ?: return@remember files
        f.applyToDto(files)
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
                contentPadding = PaddingValues(
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

    // 内嵌播放器(expect/actual 在 com.uvp.sim.ui)
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

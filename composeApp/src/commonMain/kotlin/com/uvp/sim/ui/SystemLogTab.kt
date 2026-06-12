package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SessionMarker
import com.uvp.sim.observability.SystemLog
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 系统日志 tab(spec §4 + plan §8.1 SystemLogTab):
 *
 * - 顶部:会话标识(spec Q7) + tag chip 过滤 + level 阈值下拉
 * - 列表:倒序展示,行展开看 detail
 * - 暂停跟随(spec Q5):暂停期间累积 99+,点浮条恢复跟随
 *
 * 默认 level=Info(隐藏 Debug 但保留采集,运维拉满 level 时显示)。
 */
@Composable
fun SystemLogTab(
    logs: List<SystemLog>,
    sessionMarker: SessionMarker?
) {
    var levelThreshold by remember { mutableStateOf(LogLevel.Default) }
    var tagFilter by remember { mutableStateOf<LogTag?>(null) }
    var paused by remember { mutableStateOf(false) }
    var expandedSeq by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    val visible by remember(logs, levelThreshold, tagFilter) {
        derivedStateOf {
            logs.filter { it.level.priority >= levelThreshold.priority }
                .filter { tagFilter == null || it.tag == tagFilter }
        }
    }

    var seenSize by remember { mutableStateOf(0) }
    var pausedAccum by remember(paused) { mutableStateOf(0) }
    LaunchedEffect(visible.size) {
        if (paused) {
            pausedAccum = (visible.size - seenSize).coerceIn(0, 99)
        } else {
            seenSize = visible.size
            if (visible.isNotEmpty()) listState.animateScrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (sessionMarker != null) {
            SessionHeader(sessionMarker)
            Spacer(Modifier.height(6.dp))
        }
        FilterRow(
            tagFilter = tagFilter,
            onTagChange = { tagFilter = it },
            level = levelThreshold,
            onLevelChange = { levelThreshold = it }
        )
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                reverseLayout = false
            ) {
                items(visible.size) { idx ->
                    val log = visible[visible.size - 1 - idx]  // 最新在顶
                    SystemLogRow(log, expanded = expandedSeq == log.seq) {
                        expandedSeq = if (expandedSeq == log.seq) null else log.seq
                    }
                }
            }
            if (paused && pausedAccum > 0) {
                PauseFloater(
                    count = pausedAccum,
                    onResume = {
                        paused = false
                        pausedAccum = 0
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }
            // 长按右上角空白处暂停 — 简化:点列表本身切换暂停 (P0 暂略,留导出后续)
        }
    }
}

@Composable
private fun SessionHeader(marker: SessionMarker) {
    val time = formatHms(marker.startedAtMs)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(UvpColor.PrimaryLight, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            "会话 #${marker.sessionId} · 起于 $time",
            fontSize = 11.sp,
            color = UvpColor.PrimaryDark,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun FilterRow(
    tagFilter: LogTag?,
    onTagChange: (LogTag?) -> Unit,
    level: LogLevel,
    onLevelChange: (LogLevel) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TagChip("全部", tagFilter == null) { onTagChange(null) }
            LogTag.entries.forEach { tag ->
                TagChip(tag.display, tagFilter == tag) { onTagChange(tag) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Level ≥", fontSize = 11.sp, color = UvpColor.TextSecondary)
            Spacer(Modifier.width(6.dp))
            LevelDropdown(level, onLevelChange)
        }
    }
}

@Composable
private fun LevelDropdown(level: LogLevel, onChange: (LogLevel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .background(UvpColor.Surface, RoundedCornerShape(4.dp))
                .border(1.dp, UvpColor.Border, RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                level.name,
                fontSize = 11.sp,
                color = UvpColor.Text,
                fontWeight = FontWeight.Medium
            )
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = UvpColor.TextSecondary
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LogLevel.entries.forEach { l ->
                DropdownMenuItem(
                    text = { Text(l.name, fontSize = 12.sp) },
                    onClick = {
                        onChange(l)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TagChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) UvpColor.Primary else UvpColor.Surface
    val border = if (active) UvpColor.Primary else UvpColor.Border
    val textColor = if (active) Color.White else UvpColor.TextSecondary
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = textColor)
    }
}

@Composable
private fun SystemLogRow(log: SystemLog, expanded: Boolean, onClick: () -> Unit) {
    val levelColor = when (log.level) {
        LogLevel.Debug -> UvpColor.TextHint
        LogLevel.Info -> UvpColor.Primary
        LogLevel.Warning -> UvpColor.Warning
        LogLevel.Error -> UvpColor.Danger
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatHms(log.timestampMs),
                fontSize = 10.sp,
                color = UvpColor.TextHint,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .background(levelColor.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    log.level.short,
                    fontSize = 9.sp,
                    color = levelColor,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .background(UvpColor.Surface, RoundedCornerShape(3.dp))
                    .border(1.dp, UvpColor.Border, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    log.tag.display,
                    fontSize = 9.sp,
                    color = UvpColor.TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                log.message,
                fontSize = 11.sp,
                color = UvpColor.Text,
                modifier = Modifier.weight(1f),
                maxLines = if (expanded) 10 else 1
            )
        }
        if (expanded) {
            val detail = log.detail
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UvpColor.Surface, RoundedCornerShape(4.dp))
                        .border(1.dp, UvpColor.Border, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        detail,
                        fontSize = 10.sp,
                        color = UvpColor.TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun PauseFloater(
    count: Int,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    val display = if (count >= 99) "↓ 99+ 条新消息" else "↓ $count 条新消息"
    Box(
        modifier = modifier
            .background(UvpColor.PrimaryDark, RoundedCornerShape(20.dp))
            .clickable(onClick = onResume)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(display, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

private fun Modifier.size(dp: androidx.compose.ui.unit.Dp): Modifier =
    width(dp).height(dp)

internal fun formatHms(epochMs: Long): String {
    if (epochMs <= 0) return "--:--:--"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d:%02d".format(ldt.hour, ldt.minute, ldt.second)
}

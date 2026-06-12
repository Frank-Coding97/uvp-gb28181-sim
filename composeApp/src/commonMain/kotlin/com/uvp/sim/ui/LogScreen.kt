package com.uvp.sim.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.observability.SipDialogGrouping
import kotlinx.datetime.Clock

/**
 * 日志页双 tab 容器 — SIP 日志 / 系统日志(spec §4 P0)。
 *
 * 顶部 AppBar 暴露 share/pause 入口。
 * - share: 导出当前 tab 可见内容,经平台分享面板送出
 * - pause: 仅系统日志生效,暂停跟随(累积浮条),SIP tab 上隐藏
 *
 * filter 入口故意不放 AppBar — 各 tab 内部已有 chip 行,业务级过滤更直观。
 */
@Composable
fun LogScreen(state: AppUiState) {
    var selected by remember { mutableStateOf(LogTabKind.Sip) }
    var systemPaused by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        LogAppBar(
            selected = selected,
            onSelect = { selected = it },
            systemPaused = systemPaused,
            onTogglePause = { systemPaused = !systemPaused },
            onShare = { shareCurrentTab(selected, state, systemPaused) }
        )
        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
            when (selected) {
                LogTabKind.Sip -> SipLogTab(state.events)
                LogTabKind.System -> SystemLogTab(
                    logs = state.systemEvents,
                    sessionMarker = state.sessionMarker,
                    paused = systemPaused,
                    onPausedChange = { systemPaused = it }
                )
            }
        }
    }
}

enum class LogTabKind(val label: String) {
    Sip("SIP 日志"),
    System("系统日志")
}

@Composable
private fun LogAppBar(
    selected: LogTabKind,
    onSelect: (LogTabKind) -> Unit,
    systemPaused: Boolean,
    onTogglePause: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogTabKind.entries.forEach { tab ->
            LogTabChip(tab.label, selected == tab) { onSelect(tab) }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))
        if (selected == LogTabKind.System) {
            AppBarIcon(
                icon = if (systemPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                description = if (systemPaused) "恢复跟随" else "暂停跟随",
                tint = if (systemPaused) UvpColor.Warning else UvpColor.TextSecondary,
                onClick = onTogglePause
            )
            Spacer(Modifier.width(4.dp))
        }
        AppBarIcon(
            icon = Icons.Outlined.Share,
            description = "导出当前 tab",
            tint = UvpColor.Primary,
            onClick = onShare
        )
    }
}

@Composable
private fun AppBarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun LogTabChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) UvpColor.Primary else Color.Transparent
    val textColor = if (active) Color.White else UvpColor.TextSecondary
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

private fun shareCurrentTab(
    selected: LogTabKind,
    state: AppUiState,
    @Suppress("UNUSED_PARAMETER") systemPaused: Boolean
) {
    val now = Clock.System.now().toEpochMilliseconds()
    when (selected) {
        LogTabKind.Sip -> {
            val flowEvents = state.events.toFlowEventsForExport()
            val media = state.events.toMediaSegmentsForExport()
            val items = SipDialogGrouping.group(flowEvents, media)
            val content = LogExport.formatSipFlow(items, state.sessionMarker, now)
            shareText(LogExport.filename("sip-flow", now), content)
        }
        LogTabKind.System -> {
            val content = LogExport.formatSystemLogs(
                logs = state.systemEvents,
                sessionMarker = state.sessionMarker,
                levelThreshold = com.uvp.sim.observability.LogLevel.Default,
                tagFilter = null,
                nowMs = now
            )
            shareText(LogExport.filename("system", now), content)
        }
    }
}

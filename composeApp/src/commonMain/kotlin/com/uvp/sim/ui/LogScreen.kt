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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlin.time.Clock

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
fun LogScreen(state: AppUiState, actions: AppActions) {
    var selected by remember { mutableStateOf(LogTabKind.Sip) }
    var systemPaused by remember { mutableStateOf(false) }
    var pendingClear by remember { mutableStateOf<LogTabKind?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        LogAppBar(
            selected = selected,
            onSelect = { selected = it },
            systemPaused = systemPaused,
            onTogglePause = { systemPaused = !systemPaused },
            onShare = { shareCurrentTab(selected, state, systemPaused) },
            onClear = { pendingClear = selected }
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

    pendingClear?.let { tab ->
        ClearLogConfirmDialog(
            tab = tab,
            onConfirm = {
                when (tab) {
                    LogTabKind.Sip -> actions.onClearSipLogs()
                    LogTabKind.System -> actions.onClearSystemLogs()
                }
                pendingClear = null
            },
            onDismiss = { pendingClear = null }
        )
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
    onShare: () -> Unit,
    onClear: () -> Unit
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
            icon = Icons.Outlined.DeleteOutline,
            description = "清除当前 tab",
            tint = UvpColor.Danger,
            onClick = onClear
        )
        Spacer(Modifier.width(4.dp))
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

@Composable
private fun ClearLogConfirmDialog(
    tab: LogTabKind,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清除${tab.label}") },
        text = { Text("将清空当前 tab 已累积的记录,清除后无法恢复。继续吗?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("清除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
                // 诊断导出默认带上 Debug。系统日志 UI 仍默认隐藏 Debug,但分享给 AI
                // 排查 iOS 录像链路时需要首帧/首 IDR/append drop 这些细粒度事件。
                levelThreshold = com.uvp.sim.observability.LogLevel.Debug,
                tagFilter = null,
                nowMs = now
            )
            shareText(LogExport.filename("system", now), content)
        }
    }
}

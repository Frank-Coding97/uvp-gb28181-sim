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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * 日志页双 tab 容器 — SIP 日志 / 系统日志(spec §4 P0)。
 *
 * P0 不做跨进程记忆 tab(deferred,需要 expect/actual KV) — 进程内 remember 即可。
 * SIP tab 内部由 [SipLogTab] 管列表/时序图切换;系统日志由 [SystemLogTab] 渲染。
 */
@Composable
fun LogScreen(state: AppUiState) {
    var selected by remember { mutableStateOf(LogTabKind.Sip) }

    Column(modifier = Modifier.fillMaxSize()) {
        LogTabBar(selected) { selected = it }
        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
            when (selected) {
                LogTabKind.Sip -> SipLogTab(state.events)
                LogTabKind.System -> SystemLogTab(state.systemEvents, state.sessionMarker)
            }
        }
    }
}

enum class LogTabKind(val label: String) {
    Sip("SIP 日志"),
    System("系统日志")
}

@Composable
private fun LogTabBar(active: LogTabKind, onSelect: (LogTabKind) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogTabKind.entries.forEach { tab ->
            LogTabChip(tab.label, active == tab) { onSelect(tab) }
        }
    }
}

@Composable
private fun LogTabChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) UvpColor.Primary else Color.Transparent
    val textColor = if (active) Color.White else UvpColor.TextSecondary
    Box(
        modifier = Modifier
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
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

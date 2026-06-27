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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.uvp.sim.ui.model.SessionMarkerDto

/**
 * SystemLogTab 顶部过滤栏 + 暂停浮条 + 会话头 — 纯展示控件,跟列表渲染解耦。
 *
 * 拆出原因:SystemLogTab.kt 主文件 > 400 行,过滤栏 + chip 又是独立单元;
 * 主文件聚焦数据流(filter/derivedStateOf + paused/seen 状态机)与 LazyColumn 编排。
 */

@Composable
internal fun SessionHeader(marker: SessionMarkerDto) {
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
internal fun FilterRow(
    tagFilter: LogTag?,
    onTagChange: (LogTag?) -> Unit,
    level: LogLevel,
    onLevelChange: (LogLevel) -> Unit,
    onlyErrors: Boolean,
    onOnlyErrorsChange: (Boolean) -> Unit,
    onlyThisSession: Boolean,
    onOnlyThisSessionChange: (Boolean) -> Unit,
    sessionAvailable: Boolean
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BusinessChip(
                label = "🔴 只看错误",
                active = onlyErrors,
                accent = UvpColor.Danger,
                onClick = { onOnlyErrorsChange(!onlyErrors) }
            )
            if (sessionAvailable) {
                BusinessChip(
                    label = "🎯 只看本次会话",
                    active = onlyThisSession,
                    accent = UvpColor.Primary,
                    onClick = { onOnlyThisSessionChange(!onlyThisSession) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
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
            if (onlyErrors) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "(锁定 ≥WRN)",
                    fontSize = 10.sp,
                    color = UvpColor.TextHint
                )
            }
        }
    }
}

@Composable
private fun BusinessChip(label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val bg = if (active) accent else UvpColor.Surface
    val border = if (active) accent else UvpColor.Border
    val textColor = if (active) Color.White else accent
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
internal fun PauseFloater(
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

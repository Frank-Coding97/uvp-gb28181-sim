package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.UvpColor
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * AlarmManagementScreen 用的小部件 — 字段标签、单选条、折叠头、历史行、时间格式化。
 *
 * 拆出原因:AlarmManagementScreen.kt 主文件 > 400 行,这些是纯展示部件,
 * 跟主表单状态无耦合,挪出来主文件聚焦工具栏 + 表单逻辑。
 */

@Composable
internal fun FieldLabel(text: String) {
    Text(text, fontSize = 11.sp, color = UvpColor.TextSecondary, fontWeight = FontWeight.Medium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { opt ->
            val sel = opt == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) UvpColor.PrimaryLight else Color.Transparent)
                    .border(1.dp, if (sel) UvpColor.Primary else UvpColor.Border, RoundedCornerShape(6.dp))
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    labelOf(opt),
                    fontSize = 12.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) UvpColor.Primary else UvpColor.TextSecondary
                )
            }
        }
    }
}

@Composable
internal fun CollapsibleHeader(
    icon: ImageVector,
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onToggle() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = UvpColor.TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("$title ($count)", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = UvpColor.Text)
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            null, tint = UvpColor.TextSecondary, modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun AlarmHistoryRow(time: String, typeLabel: String, desc: String, subs: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time, fontSize = 10.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp))
        Spacer(Modifier.width(6.dp))
        Text(typeLabel, fontSize = 11.sp, color = UvpColor.Warning, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text(desc, fontSize = 11.sp, color = UvpColor.TextSecondary, maxLines = 1,
            modifier = Modifier.weight(1f))
        if (subs > 0) {
            Text("${subs}推", fontSize = 10.sp, color = UvpColor.Primary, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 历史折叠区 — 随机模式 / 指定模式共用。 */
@Composable
internal fun AlarmHistorySection(
    state: AppUiState,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    CollapsibleHeader(
        icon = Icons.Outlined.History,
        title = "最近 10 条",
        count = state.alarmHistory.size,
        expanded = expanded,
        onToggle = onToggle
    )
    if (expanded) {
        if (state.alarmHistory.isEmpty()) {
            Text("暂无记录", fontSize = 11.sp, color = UvpColor.TextHint,
                modifier = Modifier.padding(start = 8.dp))
        } else {
            state.alarmHistory.asReversed().take(10).forEach { rec ->
                AlarmHistoryRow(
                    time = formatAlarmClock(rec.firedAtMs),
                    typeLabel = rec.payload.type.label,
                    desc = rec.payload.description.take(30),
                    subs = rec.notifiedSubscribers
                )
            }
        }
    }
}

/** epoch ms → HH:mm:ss 本地时间。 */
internal fun formatAlarmClock(epochMs: Long): String {
    val tz = TimeZone.currentSystemDefault()
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
    fun p2(v: Int) = v.toString().padStart(2, '0')
    return "${p2(ldt.hour)}:${p2(ldt.minute)}:${p2(ldt.second)}"
}

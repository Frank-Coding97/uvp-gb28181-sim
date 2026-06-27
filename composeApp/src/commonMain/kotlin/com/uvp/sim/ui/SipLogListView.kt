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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.SimEventDto

/**
 * SIP 协议日志列表视图(T08 补全:时间戳显示 + 行展开 + 暂停跟随 + 导出).
 *
 * 来源:从原 LogScreen.kt(249 行)拆出 — LogScreen 改成 SIP/系统双 tab 容器。
 *
 * 拆分:行 spec / event → LogRowSpec 映射 / category 过滤在 [SipLogRowSpec],
 * 单行渲染 + 报文格式化在 [SipLogRow]。
 */
@Composable
fun SipLogListView(events: List<SimEventDto>) {
    var activeFilter by remember { mutableStateOf("全部") }
    var expandedIndex by remember { mutableStateOf<Int?>(null) }
    val filtered = filterEvents(events, activeFilter)  // events 已是最新在前(SipViewModel prepend),无需再 reverse
    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        SipChipRow(activeFilter) { activeFilter = it }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(filtered.size) { idx ->
                val ev = filtered[idx]
                LogRow(ev, expanded = expandedIndex == idx) {
                    expandedIndex = if (expandedIndex == idx) null else idx
                }
            }
        }
    }
}

@Composable
private fun SipChipRow(active: String, onChip: (String) -> Unit) {
    val chips = listOf("全部", "REGISTER", "INVITE", "MESSAGE", "BYE")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.forEach { chip ->
            SipChip(chip, active == chip) { onChip(chip) }
        }
    }
}

@Composable
private fun SipChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) UvpColor.Primary else UvpColor.Surface
    val border = if (active) UvpColor.Primary else UvpColor.Border
    val textColor = if (active) Color.White else UvpColor.TextSecondary
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = textColor)
    }
}

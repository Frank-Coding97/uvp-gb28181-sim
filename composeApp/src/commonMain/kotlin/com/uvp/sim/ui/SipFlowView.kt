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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.observability.DialogRow
import com.uvp.sim.observability.FlowItem
import com.uvp.sim.observability.SipDialogGrouping
import com.uvp.sim.observability.SipFlowEvent
import com.uvp.sim.observability.MediaSegmentEvent
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * sngrep 风格时序图视图(spec §11 + plan §7.3)。
 *
 * 双列布局:
 *   ┌──── sim ──── 平台 ────┐
 *   │  → REGISTER          │  Dialog 头(可点击折叠)
 *   │  ← 401 Unauthorized  │
 *   │  → REGISTER (auth)   │
 *   │  ← 200 OK            │
 *   └──────────────────────┘
 *
 * - 心跳簇折叠态:"30 条心跳 16:42-17:42",点击展开
 * - INVITE Dialog 尾部 MediaSegment 虚线占位
 * - 时间戳精度 HH:mm:ss.SSS(毫秒)
 */
@Composable
fun SipFlowView(items: List<FlowItem>) {
    val collapsed = remember { mutableStateOf(setOf<String>()) }
    val expandedClusters = remember { mutableStateOf(setOf<Long>()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        itemsIndexed(items) { _, item ->
            when (item) {
                is FlowItem.Dialog -> DialogBlock(
                    item,
                    isCollapsed = item.callId in collapsed.value,
                    onToggle = {
                        collapsed.value = if (item.callId in collapsed.value)
                            collapsed.value - item.callId
                        else collapsed.value + item.callId
                    }
                )
                is FlowItem.HeartbeatCluster -> ClusterBlock(
                    item,
                    expanded = item.firstAtMs in expandedClusters.value,
                    onToggle = {
                        expandedClusters.value =
                            if (item.firstAtMs in expandedClusters.value)
                                expandedClusters.value - item.firstAtMs
                            else expandedClusters.value + item.firstAtMs
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DialogBlock(
    dialog: FlowItem.Dialog,
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, UvpColor.Border, RoundedCornerShape(6.dp))
    ) {
        // Call-ID 头(可点击折叠)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UvpColor.PrimaryLight)
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isCollapsed) "▶" else "▼",
                fontSize = 10.sp,
                color = UvpColor.PrimaryDark
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Dialog · ${dialog.callId.take(36)}",
                fontSize = 11.sp,
                color = UvpColor.PrimaryDark,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${dialog.rows.size} 行",
                fontSize = 10.sp,
                color = UvpColor.TextSecondary
            )
        }
        if (!isCollapsed) {
            Column(modifier = Modifier.padding(8.dp)) {
                LaneHeader()
                dialog.rows.forEach { row ->
                    when (row) {
                        is DialogRow.Message -> MessageRow(row)
                        is DialogRow.MediaSegment -> MediaSegmentRow(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun LaneHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            "sim",
            fontSize = 10.sp, color = UvpColor.TextSecondary, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            "→ 平台",
            fontSize = 10.sp, color = UvpColor.TextSecondary, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun MessageRow(msg: DialogRow.Message) {
    val arrowColor = if (msg.outgoing) UvpColor.Primary else UvpColor.Success
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatHmsMs(msg.timestampMs),
                fontSize = 9.sp,
                color = UvpColor.TextHint,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(arrowColor.copy(alpha = 0.3f))
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (msg.outgoing) "→" else "←",
                fontSize = 12.sp,
                color = arrowColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(arrowColor.copy(alpha = 0.3f))
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 1.dp)) {
            Text(
                msg.title,
                fontSize = 10.sp,
                color = arrowColor,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                textAlign = if (msg.outgoing) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End
            )
        }
        if (msg.summary.isNotEmpty()) {
            Text(
                msg.summary,
                fontSize = 9.sp,
                color = UvpColor.TextHint,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
            )
        }
    }
}

@Composable
private fun MediaSegmentRow(seg: DialogRow.MediaSegment) {
    val active = seg.stoppedAtMs == null
    val color = if (active) UvpColor.Warning else UvpColor.TextHint
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatHmsMs(seg.startedAtMs),
            fontSize = 9.sp,
            color = UvpColor.TextHint,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(6.dp))
        Text("┄┄", fontSize = 12.sp, color = color)
        Spacer(Modifier.width(6.dp))
        val tail = if (active)
            "RTP 推送中: ${seg.frameCount} 帧 / ${seg.packetCount} 包"
        else
            "RTP 已停 ${formatHmsMs(seg.stoppedAtMs ?: 0)}: ${seg.frameCount} 帧 / ${seg.packetCount} 包"
        Text(
            "($tail) → ${seg.remoteHost}:${seg.remotePort}",
            fontSize = 10.sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ClusterBlock(
    cluster: FlowItem.HeartbeatCluster,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, UvpColor.Border, RoundedCornerShape(6.dp))
            .background(UvpColor.SuccessBg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "▼" else "▶",
                fontSize = 10.sp, color = UvpColor.SuccessText
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${cluster.count} 条心跳 ${formatHmsMs(cluster.firstAtMs)} - ${formatHmsMs(cluster.lastAtMs)}",
                fontSize = 11.sp,
                color = UvpColor.SuccessText,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            cluster.rows.forEach { row -> MessageRow(row) }
        }
    }
}

private fun formatHmsMs(epochMs: Long): String {
    if (epochMs <= 0) return "--:--:--.---"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val ms = epochMs % 1000
    return "%02d:%02d:%02d.%03d".format(ldt.hour, ldt.minute, ldt.second, ms)
}

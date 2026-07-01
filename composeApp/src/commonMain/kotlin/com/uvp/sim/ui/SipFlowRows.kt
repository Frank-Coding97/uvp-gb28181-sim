package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.DialogRowDto
import com.uvp.sim.ui.model.FlowItemDto

/**
 * SipFlowView 的子行渲染:消息行(双列 sim ↔ 平台 箭头)、媒体段(虚线条)、心跳簇 chip。
 *
 * 拆出原因:SipFlowView.kt 主文件 > 400 行,这三个行渲染逻辑相对独立,
 * 移到这里让主文件聚焦 DialogCard 头部 + LazyColumn 编排。
 */

/* ─────────────────────────  消息行(双列 sim ↔ 平台)  ───────────────────────── */

@Composable
internal fun MessageLine(msg: DialogRowDto.Message) {
    val arrowColor = if (msg.outgoing) UvpColor.Primary else UvpColor.Success
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // 时间戳(秒级,加粗易读)
        Text(
            formatHmsFlow(msg.timestampMs),
            fontSize = 11.sp,
            color = UvpColor.TextSecondary,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
        // 横跨箭头(更粗 + 更深)
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(2.dp)
                    .background(arrowColor)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                if (msg.outgoing) "→" else "←",
                fontSize = 16.sp,
                color = arrowColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(2.dp)
                    .background(arrowColor)
            )
        }
        // 标题靠对应方向那侧
        Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Text(
                msg.title,
                fontSize = 13.sp,
                color = arrowColor,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                textAlign = if (msg.outgoing) TextAlign.Start else TextAlign.End
            )
        }
        if (msg.summary.isNotEmpty()) {
            Text(
                msg.summary,
                fontSize = 10.sp,
                color = UvpColor.TextSecondary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                textAlign = if (msg.outgoing) TextAlign.Start else TextAlign.End
            )
        }
    }
}

/* ─────────────────────────  RTP 段(虚线条)  ───────────────────────── */

@Composable
internal fun MediaLine(seg: DialogRowDto.MediaSegment) {
    val active = seg.stoppedAtMs == null
    val color = if (active) UvpColor.Warning else UvpColor.TextHint
    val mb = (seg.packetCount * 1500.0 / 1024 / 1024)  // 估算(每包≈1500B)
    val tail = if (active) {
        "📡 RTP 推送中 · ${seg.frameCount} 帧 / ${seg.packetCount} 包 · ${round(mb, 1)} MB"
    } else {
        "✓ RTP 已停 · ${seg.frameCount} 帧 / ${seg.packetCount} 包 · ${round(mb, 1)} MB"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧虚线竖条
        Box(
            modifier = Modifier
                .width(64.dp)
                .padding(end = 4.dp)
        ) {
            Text(
                formatHmsFlow(seg.startedAtMs),
                fontSize = 11.sp,
                color = UvpColor.TextSecondary,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
        Box(
            modifier = Modifier
                .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Column {
                Text(
                    tail,
                    fontSize = 11.sp,
                    color = color,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "→ ${seg.remoteHost}:${seg.remotePort}",
                    fontSize = 10.sp,
                    color = UvpColor.TextHint,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/* ─────────────────────────  心跳簇(克制 chip 风格)  ───────────────────────── */

@Composable
internal fun ClusterChip(
    cluster: FlowItemDto.HeartbeatCluster,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "▾" else "▸",
                fontSize = 12.sp,
                color = UvpColor.TextSecondary,
                modifier = Modifier.width(14.dp)
            )
            Text(
                "💓",
                fontSize = 12.sp
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${cluster.count} 条心跳",
                fontSize = 12.sp,
                color = UvpColor.Text,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${formatHmsFlow(cluster.firstAtMs)} - ${formatHmsFlow(cluster.lastAtMs)}",
                fontSize = 11.sp,
                color = UvpColor.TextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            cluster.rows.forEach { row -> MessageLine(row) }
        }
    }
}

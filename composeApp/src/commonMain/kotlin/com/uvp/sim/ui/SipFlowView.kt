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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.uvp.sim.observability.DialogRow
import com.uvp.sim.observability.FlowItem
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * sngrep 风格时序图(P1 重设计版)。
 *
 * 设计原则:
 * - 运维视角:Dialog 头显示业务名(注册 / 视频点播)而非 hex Call-ID
 * - 单列时间轴:替代之前的双列 sim/平台 lane(信息密度更高)
 * - 信号克制:主色 = 灰阶 + 蓝(发出)/ 绿(收到)/ 橙(媒体)
 * - 时间戳:HH:mm:ss 主时间(秒级即可识别),毫秒在小副字
 * - 折叠默认:Dialog 默认展开,心跳簇默认折叠
 */
@Composable
fun SipFlowView(items: List<FlowItem>) {
    val collapsed = remember { mutableStateOf(setOf<String>()) }
    val expandedClusters = remember { mutableStateOf(setOf<Long>()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        itemsIndexed(items) { _, item ->
            when (item) {
                is FlowItem.Dialog -> DialogCard(
                    item,
                    isCollapsed = item.callId in collapsed.value,
                    onToggle = {
                        collapsed.value = if (item.callId in collapsed.value)
                            collapsed.value - item.callId
                        else collapsed.value + item.callId
                    }
                )
                is FlowItem.HeartbeatCluster -> ClusterChip(
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
            Spacer(Modifier.height(10.dp))
        }
    }
}

/* ─────────────────────────  Dialog 卡片  ───────────────────────── */

@Composable
private fun DialogCard(
    dialog: FlowItem.Dialog,
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    val title = dialogBusinessTitle(dialog)
    val timeRange = "${formatHmsFlow(dialog.startedAtMs)} - ${formatHmsFlow(dialog.rows.lastTimestamp() ?: dialog.startedAtMs)}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
    ) {
        // ── Dialog 头 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isCollapsed) "▸" else "▾",
                fontSize = 12.sp,
                color = UvpColor.TextSecondary,
                modifier = Modifier.width(14.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 13.sp,
                    color = UvpColor.Text,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "$timeRange · ${dialog.rows.size} 条",
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        if (!isCollapsed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(UvpColor.Border)
            )
            // 双列 lane 头
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    "sim",
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "平台",
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                dialog.rows.forEach { row ->
                    when (row) {
                        is DialogRow.Message -> MessageLine(row)
                        is DialogRow.MediaSegment -> MediaLine(row)
                    }
                }
            }
        }
    }
}

/* ─────────────────────────  消息行(双列 sim ↔ 平台)  ───────────────────────── */

@Composable
private fun MessageLine(msg: DialogRow.Message) {
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
                textAlign = if (msg.outgoing)
                    androidx.compose.ui.text.style.TextAlign.Start
                else
                    androidx.compose.ui.text.style.TextAlign.End
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
                textAlign = if (msg.outgoing)
                    androidx.compose.ui.text.style.TextAlign.Start
                else
                    androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}

/* ─────────────────────────  RTP 段(虚线条)  ───────────────────────── */

@Composable
private fun MediaLine(seg: DialogRow.MediaSegment) {
    val active = seg.stoppedAtMs == null
    val color = if (active) UvpColor.Warning else UvpColor.TextHint
    val mb = (seg.packetCount * 1500.0 / 1024 / 1024)  // 估算(每包≈1500B)
    val tail = if (active) {
        "📡 RTP 推送中 · ${seg.frameCount} 帧 / ${seg.packetCount} 包 · ${"%.1f".format(mb)} MB"
    } else {
        "✓ RTP 已停 · ${seg.frameCount} 帧 / ${seg.packetCount} 包 · ${"%.1f".format(mb)} MB"
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
private fun ClusterChip(
    cluster: FlowItem.HeartbeatCluster,
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

/* ─────────────────────────  辅助:业务名推断 + 时间格式化  ───────────────────────── */

private fun List<DialogRow>.lastTimestamp(): Long? = mapNotNull {
    when (it) {
        is DialogRow.Message -> it.timestampMs
        is DialogRow.MediaSegment -> it.stoppedAtMs ?: it.startedAtMs
    }
}.maxOrNull()

/**
 * 把 hex Call-ID 翻译成业务名 — 运维一眼能看懂。
 *
 * 启发式:看 Dialog 第一条消息的 SIP method,推断业务类型。
 * 备用:hex 短 hash 后缀作 trace token(老板要 grep 时用)。
 */
private fun dialogBusinessTitle(dialog: FlowItem.Dialog): String {
    val first = dialog.rows.firstOrNull() as? DialogRow.Message
    val token = dialog.callId.substringBefore('@').take(6)

    val biz = when (val msg = first?.rawMessage) {
        is SipRequest -> when (msg.method) {
            SipMethod.REGISTER -> "📡 注册"
            SipMethod.INVITE -> {
                val channel = msg.requestUri.substringAfter("sip:").substringBefore('@')
                "🎬 视频点播 · 通道 ${channel.takeLast(3)}"
            }
            SipMethod.MESSAGE -> {
                val body = msg.body.decodeToString()
                when {
                    "<CmdType>Catalog</CmdType>" in body -> "📂 目录查询"
                    "<CmdType>DeviceInfo</CmdType>" in body -> "📋 设备信息"
                    "<CmdType>Alarm</CmdType>" in body -> "🚨 告警上报"
                    else -> "💬 MESSAGE"
                }
            }
            SipMethod.BYE -> "✋ 结束通话"
            else -> msg.method.name
        }
        is SipResponse -> "↩ ${msg.statusCode}"
        else -> "Dialog"
    }
    return "$biz  · #$token"
}

private fun formatHmsFlow(epochMs: Long): String {
    if (epochMs <= 0) return "--:--:--"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d:%02d".format(ldt.hour, ldt.minute, ldt.second)
}

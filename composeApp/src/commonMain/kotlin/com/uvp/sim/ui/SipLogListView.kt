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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * SIP 协议日志列表视图(T08 补全:时间戳显示 + 行展开 + 暂停跟随 + 导出).
 *
 * 来源:从原 LogScreen.kt(249 行)拆出 — LogScreen 改成 SIP/系统双 tab 容器。
 */
@Composable
fun SipLogListView(events: List<SimEvent>) {
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

@Composable
private fun LogRow(ev: SimEvent, expanded: Boolean, onClick: () -> Unit) {
    val spec = logRowSpec(ev) ?: return
    val time = formatHmsList(ev.timestampMs)
    val clipboard = LocalClipboardManager.current
    val toast = LocalToastHost.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .run { if (spec.highlight) background(UvpColor.WarningBg) else this }
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, fontSize = 10.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Text(
                spec.arrow,
                fontSize = 10.sp,
                color = if (spec.outgoing) UvpColor.Primary else UvpColor.Success,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            MethodBadge(spec.method, spec.badgeColor)
            Spacer(Modifier.width(8.dp))
            Text(
                spec.content,
                fontSize = 11.sp,
                color = UvpColor.Text,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) 8 else 1,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded) {
            val raw = rawSipBody(ev)
            if (raw.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                // 工具栏:右对齐复制按钮(避免遮挡 raw 文本起头)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(UvpColor.Bg, RoundedCornerShape(3.dp))
                            .border(1.dp, UvpColor.Border, RoundedCornerShape(3.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(raw))
                                toast.success("已复制到剪贴板")
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "复制 SIP 报文",
                                modifier = Modifier.size(11.dp),
                                tint = UvpColor.TextSecondary
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "复制",
                                fontSize = 9.sp,
                                color = UvpColor.TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UvpColor.Surface, RoundedCornerShape(4.dp))
                        .border(1.dp, UvpColor.Border, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        raw,
                        fontSize = 10.sp,
                        color = UvpColor.TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun rawSipBody(ev: SimEvent): String {
    val msg = when (ev) {
        is SimEvent.MessageSent -> ev.message
        is SimEvent.MessageReceived -> ev.message
        else -> return ""
    }
    return buildString {
        when (msg) {
            is SipRequest -> appendLine("${msg.method} ${msg.requestUri} SIP/2.0")
            is SipResponse -> appendLine("SIP/2.0 ${msg.statusCode} ${msg.reasonPhrase}")
        }
        msg.headers.forEach { appendLine("${it.name}: ${it.value}") }
        if (msg.body.isNotEmpty()) {
            appendLine()
            append(msg.body.decodeToString().take(800))
        }
    }
}

@Composable
private fun MethodBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color,
            fontFamily = FontFamily.Monospace)
    }
}

private data class LogRowSpec(
    val time: String,
    val arrow: String,
    val outgoing: Boolean,
    val method: String,
    val badgeColor: Color,
    val content: String,
    val highlight: Boolean = false,
    val category: String = ""
)

private fun logRowSpec(ev: SimEvent): LogRowSpec? = when (ev) {
    is SimEvent.RegistrationStarted -> LogRowSpec(
        "", "→", true, "REG", UvpColor.Primary, "sip:${ev.server}", category = "REGISTER"
    )
    is SimEvent.RegistrationChallenged -> LogRowSpec(
        "", "←", false, "401", UvpColor.Warning, "认证挑战", category = "REGISTER"
    )
    is SimEvent.RegistrationSucceeded -> LogRowSpec(
        "", "←", false, "200", UvpColor.Success, "OK · 注册成功 expires=${ev.expiresSeconds}s", category = "REGISTER"
    )
    is SimEvent.RegistrationFailed -> LogRowSpec(
        "", "←", false, "ERR", UvpColor.Danger, ev.reason, category = "REGISTER"
    )
    is SimEvent.HeartbeatSent -> LogRowSpec(
        "", "→", true, "MSG", UvpColor.Info, "Keepalive · CSeq ${ev.sequence}", category = "MESSAGE"
    )
    is SimEvent.HeartbeatAcknowledged -> LogRowSpec(
        "", "←", false, "200", UvpColor.Success, "OK · 心跳确认", category = "MESSAGE"
    )
    is SimEvent.IncomingInvite -> LogRowSpec(
        "", "←", false, "INV", UvpColor.Warning, "SDP m=video · ${ev.callId.take(20)}",
        highlight = true, category = "INVITE"
    )
    is SimEvent.StreamStarted -> LogRowSpec(
        "", "→", true, "200", UvpColor.Success, "OK · SDP answer → RTP ${ev.remoteHost}:${ev.remotePort}",
        category = "INVITE"
    )
    is SimEvent.StreamStopped -> LogRowSpec(
        "", "■", true, "END", UvpColor.TextHint, "${ev.frameCount}f / ${ev.packetCount}p · ${ev.reason}",
        category = "BYE"
    )
    is SimEvent.StreamStats -> null  // 时序图 MediaSegment 显示,列表不重复
    is SimEvent.CallEnded -> LogRowSpec(
        "", "←", false, "BYE", UvpColor.Danger, ev.reason, category = "BYE"
    )
    is SimEvent.SnapshotReported -> LogRowSpec(
        "", "→", true, "ALM", UvpColor.Warning, "抓拍 SN=${ev.sn}", category = "MESSAGE"
    )
    is SimEvent.SnapshotUploaded -> LogRowSpec(
        "", "→", true, "SNP", UvpColor.Success,
        "抓拍上传 ${ev.count}/${ev.total} · ${ev.snapShotId}",
        category = "MESSAGE"
    )
    is SimEvent.SnapshotUploadFailed -> LogRowSpec(
        "", "⚠", true, "SNP", UvpColor.Danger,
        "抓拍上传失败 · ${ev.snapShotId}",
        highlight = true,
        category = "MESSAGE"
    )
    is SimEvent.MessageSent -> LogRowSpec(
        "", "→", true, msgMethodShort(ev.message), UvpColor.Primary,
        msgContent(ev.message), category = msgCategory(ev.message)
    )
    is SimEvent.MessageReceived -> LogRowSpec(
        "", "←", false, msgMethodShort(ev.message), UvpColor.Success,
        msgContent(ev.message), category = msgCategory(ev.message)
    )
    is SimEvent.TransportError -> LogRowSpec(
        "", "⚠", true, "ERR", UvpColor.Danger, ev.description, category = ""
    )
    is SimEvent.DeviceControlReceived -> LogRowSpec(
        "", "←", false, "CTL", UvpColor.Info,
        "${ev.commandType} · ${ev.detail.take(40)}",
        category = "CONTROL"
    )
    is SimEvent.SubscribeReceived -> LogRowSpec(
        "", "←", false, "SUB", UvpColor.Info, "${ev.kind} · from=${ev.subscriber}", category = "SUBSCRIBE"
    )
    is SimEvent.NotifySent -> LogRowSpec(
        "", "→", true, "NTF", UvpColor.Primary, "${ev.kind} · SN=${ev.sn}", category = "NOTIFY"
    )
    is SimEvent.SubscribeExpired -> LogRowSpec(
        "", "·", true, "EXP", UvpColor.TextHint, "${ev.kind} 过期", category = "SUBSCRIBE"
    )
    is SimEvent.SubscribeRefreshed -> LogRowSpec(
        "", "←", false, "REF", UvpColor.Success, "续订 expires=${ev.newExpiresSeconds}s", category = "SUBSCRIBE"
    )
    is SimEvent.HeartbeatTimeoutDetected -> LogRowSpec(
        "", "⚠", true, "HB!", UvpColor.Danger,
        "心跳连续 ${ev.missedCount}/${ev.maxAllowed} 未响应",
        highlight = true, category = "MESSAGE"
    )
    is SimEvent.AutoReregisterTriggered -> LogRowSpec(
        "", "↻", true, "RE", UvpColor.Warning, "自动重注册 · ${ev.reason}",
        highlight = true, category = "REGISTER"
    )
    is SimEvent.RegistrationRetryScheduled -> LogRowSpec(
        "", "↻", true, "TRY", UvpColor.Info,
        "第 ${ev.attempt} 次重试 · ${ev.delayMs}ms 后", category = "REGISTER"
    )
    is SimEvent.InviteAckTimeout -> LogRowSpec(
        "", "⚠", true, "ACK", UvpColor.Warning,
        "平台 ACK 未到达 · ${ev.callId.take(20)}",
        highlight = true, category = "INVITE"
    )
    is SimEvent.DeviceControlReceived -> LogRowSpec(
        "", "←", false, "CTRL", UvpColor.Info,
        "设备控制 · ${ev.commandType} · ${ev.detail.take(30)}",
        category = "CONTROL"
    )
    is SimEvent.AlarmFired -> LogRowSpec(
        "", "→", true, "ALM", UvpColor.Warning,
        "报警 · ${ev.type.label}/${ev.priority.label} · ${ev.description.take(30)}",
        highlight = true, category = "ALARM"
    )
    is SimEvent.AlarmReset -> LogRowSpec(
        "", "·", true, "RST", UvpColor.Info,
        "报警复位 · ${alarmResetBy(ev.by)}", category = "ALARM"
    )
    is SimEvent.AlarmSubscribed -> LogRowSpec(
        "", "←", false, "SUB", UvpColor.Info,
        "报警订阅 · from=${ev.subscriber} expires=${ev.expires}s", category = "SUBSCRIBE"
    )
    is SimEvent.AlarmNotifySent -> LogRowSpec(
        "", "→", true, "NTF", UvpColor.Primary,
        "报警 NOTIFY · SN=${ev.sn} → ${ev.subscriber}", category = "NOTIFY"
    )
    is SimEvent.AlarmSubscriptionExpired -> LogRowSpec(
        "", "·", true, "EXP", UvpColor.TextHint,
        "报警订阅过期 · ${ev.subscriber}", category = "SUBSCRIBE"
    )
    is SimEvent.BroadcastReceived -> LogRowSpec(
        "", "←", false, "BC", UvpColor.Info,
        "语音广播请求 · source=${ev.sourceId}", highlight = true, category = "BROADCAST"
    )
    is SimEvent.BroadcastInvited -> LogRowSpec(
        "", "→", true, "INV", UvpColor.Primary,
        "反向 INVITE → ${ev.platformUri} · 本地端口 ${ev.localPort}", category = "BROADCAST"
    )
    is SimEvent.BroadcastStarted -> LogRowSpec(
        "", "♪", false, "RX", UvpColor.Success,
        "对讲音频开始 · 首包 ${ev.firstPacketDelayMs}ms", category = "BROADCAST"
    )
    is SimEvent.BroadcastPacketRx -> null  // 媒体接收统计,不刷 SIP 信令日志(浮动/指示器读 currentBroadcast)
    is SimEvent.BroadcastEnded -> LogRowSpec(
        "", "■", true, "END", UvpColor.TextHint,
        "对讲结束 · ${ev.reason} · ${ev.durationMs}ms", category = "BROADCAST"
    )
}

private fun alarmResetBy(by: SimEvent.ResetSource): String = when (by) {
    is SimEvent.ResetSource.Local -> "本地"
    is SimEvent.ResetSource.Remote -> "平台 ${by.subscriber}"
}

private fun msgMethodShort(m: SipMessage): String = when (m) {
    is SipRequest -> when (m.method) {
        SipMethod.REGISTER -> "REG"
        SipMethod.INVITE -> "INV"
        SipMethod.MESSAGE -> "MSG"
        SipMethod.BYE -> "BYE"
        else -> m.method.name.take(3)
    }
    is SipResponse -> "${m.statusCode}"
}

private fun msgContent(m: SipMessage): String = when (m) {
    is SipRequest -> "${m.method.name} ${m.requestUri.take(30)}"
    is SipResponse -> "${m.statusCode} ${m.reasonPhrase}"
}

private fun msgCategory(m: SipMessage): String = when (m) {
    is SipRequest -> m.method.name
    is SipResponse -> {
        val cseq = m.headers.firstOrNull {
            it.name.equals("CSeq", ignoreCase = true)
        }?.value ?: ""
        when {
            cseq.contains("REGISTER") -> "REGISTER"
            cseq.contains("INVITE") -> "INVITE"
            cseq.contains("MESSAGE") -> "MESSAGE"
            cseq.contains("BYE") -> "BYE"
            else -> ""
        }
    }
}

private fun filterEvents(events: List<SimEvent>, filter: String): List<SimEvent> {
    if (filter == "全部") return events
    return events.filter { ev ->
        val spec = logRowSpec(ev) ?: return@filter false
        spec.category == filter
    }
}

private fun formatHmsList(epochMs: Long): String {
    if (epochMs <= 0) return "--:--:--"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d:%02d".format(ldt.hour, ldt.minute, ldt.second)
}

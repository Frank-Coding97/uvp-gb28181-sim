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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse

/**
 * 协议日志 Log screen — 1:1 还原 index-v1.html § SCREEN 4.
 *
 * - 顶部 chip 过滤(全部/REGISTER/INVITE/MESSAGE/BYE)
 * - 每行:时间 + →/← 箭头 + 方法徽章(着色) + 内容摘要
 * - M1 暂不做展开报文(M2 补)
 */
@Composable
fun LogScreen(state: AppUiState) {
    var activeFilter by remember { mutableStateOf("全部") }
    val filtered = filterEvents(state.events, activeFilter)
    val listState = rememberLazyListState()
    LaunchedEffect(state.events.size) {
        if (state.events.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        ChipRow(activeFilter) { activeFilter = it }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(filtered) { ev ->
                LogRow(ev)
            }
        }
    }
}

@Composable
private fun ChipRow(active: String, onChip: (String) -> Unit) {
    val chips = listOf("全部", "REGISTER", "INVITE", "MESSAGE", "BYE")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.forEach { chip ->
            Chip(chip, active == chip) { onChip(chip) }
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
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
private fun LogRow(ev: SimEvent) {
    val spec = logRowSpec(ev) ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .run { if (spec.highlight) background(UvpColor.WarningBg) else this }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(spec.time, fontSize = 10.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace)
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
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
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
    is SimEvent.CallEnded -> LogRowSpec(
        "", "←", false, "BYE", UvpColor.Danger, ev.reason, category = "BYE"
    )
    is SimEvent.SnapshotReported -> LogRowSpec(
        "", "→", true, "ALM", UvpColor.Warning, "抓拍 SN=${ev.sn}", category = "MESSAGE"
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

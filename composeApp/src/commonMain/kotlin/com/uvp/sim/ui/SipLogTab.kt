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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.observability.MediaSegmentEvent
import com.uvp.sim.observability.SipDialogGrouping
import com.uvp.sim.observability.SipFlowEvent
import com.uvp.sim.sip.SipMessage

/**
 * SIP 日志 tab — 内部切"列表 / 时序图"两种视图(spec §11 sngrep 风格)。
 */
@Composable
fun SipLogTab(events: List<SimEvent>) {
    var mode by remember { mutableStateOf(SipViewMode.List) }

    val flowItems by remember(events) {
        derivedStateOf {
            val flowEvents = events.toFlowEvents()
            val media = events.toMediaSegments()
            SipDialogGrouping.group(flowEvents, media)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ViewModeBar(mode) { mode = it }
        Box(modifier = Modifier.fillMaxSize()) {
            when (mode) {
                SipViewMode.List -> SipLogListView(events)
                SipViewMode.Flow -> SipFlowView(flowItems)
            }
        }
    }
}

enum class SipViewMode(val label: String) {
    List("列表"),
    Flow("时序图")
}

@Composable
private fun ViewModeBar(active: SipViewMode, onSelect: (SipViewMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SipViewMode.entries.forEach { m ->
            ToggleChip(m.label, active == m) { onSelect(m) }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) UvpColor.Primary else UvpColor.Surface
    val textColor = if (active) Color.White else UvpColor.TextSecondary
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 11.sp, color = textColor, fontWeight = FontWeight.Medium)
    }
}

/**
 * 把 SimEvent 列表里的 SIP 消息抽出来 → SipFlowEvent。
 *
 * 用每条 SimEvent.timestampMs(emit 时的真实时间)。
 */
private fun List<SimEvent>.toFlowEvents(): List<SipFlowEvent> {
    return mapNotNull { ev ->
        val (msg, outgoing) = when (ev) {
            is SimEvent.MessageSent -> ev.message to true
            is SimEvent.MessageReceived -> ev.message to false
            else -> return@mapNotNull null
        }
        val callId = msg.firstHeader("Call-ID") ?: return@mapNotNull null
        SipFlowEvent(
            timestampMs = ev.timestampMs,
            outgoing = outgoing,
            message = msg,
            callId = callId
        )
    }
}

/**
 * 把 StreamStarted/Stopped/Stats 配对成 MediaSegmentEvent。
 *
 * 每个 callId 取最新的 StreamStarted,frameCount/packetCount 优先取 StreamStats
 * (流仍在推时实时更新),没有 stats 才退到 StreamStopped(流已停)。
 *
 * 简化:M1 至多 1 路并发流,只取最后一路。
 */
private fun List<SimEvent>.toMediaSegments(): List<MediaSegmentEvent> {
    val started = filterIsInstance<SimEvent.StreamStarted>().lastOrNull() ?: return emptyList()
    val stoppedAt = filterIsInstance<SimEvent.StreamStopped>()
        .lastOrNull { it.callId == started.callId }
    val latestStats = filterIsInstance<SimEvent.StreamStats>()
        .lastOrNull { it.callId == started.callId }
    val frameCount = stoppedAt?.frameCount ?: latestStats?.frameCount ?: 0
    val packetCount = stoppedAt?.packetCount ?: latestStats?.packetCount ?: 0
    return listOf(
        MediaSegmentEvent(
            callId = started.callId,
            startedAtMs = started.timestampMs,
            stoppedAtMs = stoppedAt?.timestampMs,
            frameCount = frameCount,
            packetCount = packetCount,
            remoteHost = started.remoteHost,
            remotePort = started.remotePort
        )
    )
}

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
import com.uvp.sim.observability.MediaSegmentEvent
import com.uvp.sim.observability.SipDialogGrouping
import com.uvp.sim.observability.SipFlowEvent
import com.uvp.sim.ui.model.SimEventDto
import com.uvp.sim.ui.model.SipMessageDto
import com.uvp.sim.ui.model.mapper.toDto

/**
 * SIP 日志 tab — 内部切"列表 / 时序图"两种视图(spec §11 sngrep 风格)。
 */
@Composable
fun SipLogTab(events: List<SimEventDto>) {
    var mode by remember { mutableStateOf(SipViewMode.List) }

    val flowItems by remember(events) {
        derivedStateOf {
            val flowEvents = events.toFlowEvents()
            val media = events.toMediaSegments()
            SipDialogGrouping.group(flowEvents, media).map { it.toDto() }
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
 * 把 SimEventDto 列表里的 SIP 消息抽出来 → SipFlowEvent。
 *
 * 用每条 SimEventDto.timestampMs(emit 时的真实时间)。
 */
internal fun List<SimEventDto>.toFlowEventsForExport(): List<SipFlowEvent> {
    return mapNotNull { ev ->
        val (msg, outgoing) = when (ev) {
            is SimEventDto.MessageSent -> ev.message to true
            is SimEventDto.MessageReceived -> ev.message to false
            else -> return@mapNotNull null
        }
        val callId = msg.callIdHeader() ?: return@mapNotNull null
        SipFlowEvent(
            timestampMs = ev.timestampMs,
            outgoing = outgoing,
            message = msg.toSipMessage(),
            callId = callId
        )
    }
}

private fun SipMessageDto.callIdHeader(): String? =
    headers.firstOrNull { it.name.equals("Call-ID", ignoreCase = true) }?.value

/**
 * 反向重建 SipMessage(仅本文件内部使用).T4.3e 接 events: List<SimEventDto> 时,
 * SipDialogGrouping / SipFlowEvent / MediaSegmentEvent 仍是 shared.observability 类型,
 * 需要从 SipMessageDto 反向重建 SipMessage 喂给它们.
 *
 * 字段无损映射:Request 仅取 method 名做 SipMethod.valueOf 反查;body 是 UTF-8 decoded String,
 * encodeToByteArray() 反向(SDP/MANSCDP+XML 均为 UTF-8 文本,实际无损).
 *
 * G8 歧义(老板拍板):此 helper 是 DTO 原则破口,理由 = SipFlowEvent / DialogGrouping
 * 在 shared 不动 PR-A 范围.PR-A-2 切 SipFlowEvent → SipFlowEventDto 后此 helper 可删.
 */
private fun SipMessageDto.toSipMessage(): com.uvp.sim.sip.SipMessage {
    val sharedHeaders = headers.map { com.uvp.sim.sip.SipMessage.Header(it.name, it.value) }
    val bodyBytes = body.encodeToByteArray()
    return when (this) {
        is SipMessageDto.Request -> com.uvp.sim.sip.SipRequest(
            method = com.uvp.sim.sip.SipMethod.valueOf(method.name),
            requestUri = requestUri,
            sipVersion = sipVersion,
            headers = sharedHeaders,
            body = bodyBytes,
        )
        is SipMessageDto.Response -> com.uvp.sim.sip.SipResponse(
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            sipVersion = sipVersion,
            headers = sharedHeaders,
            body = bodyBytes,
        )
    }
}

private fun List<SimEventDto>.toFlowEvents(): List<SipFlowEvent> = toFlowEventsForExport()

/**
 * 把 StreamStarted/Stopped/Stats 配对成 MediaSegmentEvent。
 *
 * 每个 callId 取最新的 StreamStarted,frameCount/packetCount 优先取 StreamStats
 * (流仍在推时实时更新),没有 stats 才退到 StreamStopped(流已停)。
 *
 * 简化:M1 至多 1 路并发流,只取最后一路。
 */
internal fun List<SimEventDto>.toMediaSegmentsForExport(): List<MediaSegmentEvent> {
    val started = filterIsInstance<SimEventDto.StreamStarted>().lastOrNull() ?: return emptyList()
    val stoppedAt = filterIsInstance<SimEventDto.StreamStopped>()
        .lastOrNull { it.callId == started.callId }
    val latestStats = filterIsInstance<SimEventDto.StreamStats>()
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

private fun List<SimEventDto>.toMediaSegments(): List<MediaSegmentEvent> = toMediaSegmentsForExport()

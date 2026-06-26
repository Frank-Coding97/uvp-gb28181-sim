package com.uvp.sim.ui.model.mapper

import com.uvp.sim.observability.DialogRow
import com.uvp.sim.observability.FlowItem
import com.uvp.sim.observability.SipFlowEvent
import com.uvp.sim.ui.model.DialogRowDto
import com.uvp.sim.ui.model.FlowItemDto
import com.uvp.sim.ui.model.SipFlowEventDto

/** PR-A T4.2 实现. 嵌套 SipMessage.toDto(). */

fun SipFlowEvent.toDto(): SipFlowEventDto = SipFlowEventDto(
    timestampMs = timestampMs,
    outgoing = outgoing,
    message = message.toDto(),
    callId = callId,
)

fun DialogRow.toDto(): DialogRowDto = when (this) {
    is DialogRow.Message -> DialogRowDto.Message(
        timestampMs = timestampMs,
        outgoing = outgoing,
        title = title,
        summary = summary,
        rawMessage = rawMessage.toDto(),
    )
    is DialogRow.MediaSegment -> DialogRowDto.MediaSegment(
        startedAtMs = startedAtMs,
        stoppedAtMs = stoppedAtMs,
        frameCount = frameCount,
        packetCount = packetCount,
        callId = callId,
        remoteHost = remoteHost,
        remotePort = remotePort,
    )
}

fun FlowItem.toDto(): FlowItemDto = when (this) {
    is FlowItem.Dialog -> FlowItemDto.Dialog(
        callId = callId,
        startedAtMs = startedAtMs,
        rows = rows.map { it.toDto() },
    )
    is FlowItem.HeartbeatCluster -> FlowItemDto.HeartbeatCluster(
        firstAtMs = firstAtMs,
        lastAtMs = lastAtMs,
        count = count,
        rows = rows.map { it.toDto() as DialogRowDto.Message },
    )
}

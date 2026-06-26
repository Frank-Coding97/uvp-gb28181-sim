package com.uvp.sim.ui.model

/**
 * UI 层 SIP 信令流事件 DTO. 1:1 映射 com.uvp.sim.observability.SipFlowEvent.
 * 嵌套 SipMessageDto.
 */
data class SipFlowEventDto(
    val timestampMs: Long,
    val outgoing: Boolean,
    val message: SipMessageDto,
    val callId: String,
)

/**
 * UI 层 SIP dialog 行 DTO. 1:1 映射 com.uvp.sim.observability.DialogRow.
 * 2 variant sealed (Message / MediaSegment).
 */
sealed class DialogRowDto {
    data class Message(
        val timestampMs: Long,
        val outgoing: Boolean,
        val title: String,
        val summary: String,
        val rawMessage: SipMessageDto,
    ) : DialogRowDto()
    data class MediaSegment(
        val startedAtMs: Long,
        val stoppedAtMs: Long?,
        val frameCount: Int,
        val packetCount: Int,
        val callId: String,
        val remoteHost: String,
        val remotePort: Int,
    ) : DialogRowDto()
}

/**
 * UI 层 SIP 流分组项 DTO. 1:1 映射 com.uvp.sim.observability.FlowItem.
 * 2 variant sealed (Dialog / HeartbeatCluster).
 */
sealed class FlowItemDto {
    data class Dialog(
        val callId: String,
        val startedAtMs: Long,
        val rows: List<DialogRowDto>,
    ) : FlowItemDto()
    data class HeartbeatCluster(
        val firstAtMs: Long,
        val lastAtMs: Long,
        val count: Int,
        val rows: List<DialogRowDto.Message>,
    ) : FlowItemDto()
}

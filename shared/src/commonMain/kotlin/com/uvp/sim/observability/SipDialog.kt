package com.uvp.sim.observability

import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse

/**
 * sngrep 风格时序图的展示层数据。
 *
 * 来源:[com.uvp.sim.domain.SimEvent] 经 [SipDialogGrouping.group] 派生。
 * 不直接绑业务事件类型 — UI 渲染层只看本文件的结构。
 *
 * **设计要点**:
 * - Dialog 按 Call-ID 聚合(REGISTER 401-200 也属同 Call-ID)
 * - HeartbeatCluster 折叠连续心跳(spec Q3),邻接、同 CSeq 序列、< 5min 间隔
 * - MediaSegment 挂在 INVITE Dialog 尾部(spec Q9),虚线占位 RTP 阶段
 */
sealed class FlowItem {
    data class Dialog(
        val callId: String,
        val startedAtMs: Long,
        val rows: List<DialogRow>
    ) : FlowItem()

    data class HeartbeatCluster(
        val firstAtMs: Long,
        val lastAtMs: Long,
        val count: Int,
        val rows: List<DialogRow.Message>
    ) : FlowItem()
}

sealed class DialogRow {
    data class Message(
        val timestampMs: Long,
        val outgoing: Boolean,
        val title: String,        // "REGISTER" / "200 OK" / "INVITE"
        val summary: String,      // request URI 或 reason phrase
        val rawMessage: SipMessage
    ) : DialogRow()

    data class MediaSegment(
        val startedAtMs: Long,
        val stoppedAtMs: Long?,   // null 表示还在推
        val frameCount: Int,
        val packetCount: Int,
        val callId: String,
        val remoteHost: String,
        val remotePort: Int
    ) : DialogRow()
}

/**
 * 描述时序图分组所需的 SIP 事件输入(从 SimEvent 提取后的最小结构)。
 *
 * 这里不直接吃 SimEvent 是为了:
 * 1. commonMain 算法层不依赖 domain 包 → 测试可单独跑
 * 2. 未来若 SimEvent 重构,只改适配层 [SipFlowEventAdapter] 即可
 */
data class SipFlowEvent(
    val timestampMs: Long,
    val outgoing: Boolean,
    val message: SipMessage,
    /** 关联到哪个 SIP Call-ID(取自消息头);心跳 MESSAGE 也走这里。 */
    val callId: String
)

/**
 * 媒体段输入。来源:SimEvent.StreamStarted/StreamStopped。
 *
 * RTP 推送中(stoppedAtMs == null)由"当前活跃 Stream 计数"驱动 —
 * 时序图渲染时实时显示帧/包数。
 */
data class MediaSegmentEvent(
    val callId: String,
    val startedAtMs: Long,
    val stoppedAtMs: Long?,
    val frameCount: Int,
    val packetCount: Int,
    val remoteHost: String,
    val remotePort: Int
)

package com.uvp.sim.domain.coord

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 主叫推流(实时流)对话域。
 *
 * 接管的 SIP 流程(plan 第 2.2 节):
 * - INVITE(实时流,SDP s= 缺省 / Play)
 * - ACK / BYE / CANCEL
 * - 200 OK 响应 → SDP 协商
 * - ActiveStream 状态(dialog / RTP 通道 / ACK 计时)
 *
 * 来自 SimulatorEngine 的方法迁移清单:handleInvite / handleAck / handleBye /
 * handleCancel / stopActiveStream / extractInviteTarget / classifyInviteTarget /
 * buildSdpMediaSpec / startInboundIfNeeded + 内部 ActiveStream data class
 */
internal interface InviteCoordinator : Coordinator {
    val state: StateFlow<InviteState>
    val events: SharedFlow<InviteEvent>
    val activeStreamSnapshot: StateFlow<ActiveStreamSnapshot?>

    /**
     * 用户主动停流(UI 触发 / 错误恢复)。
     * 发 BYE,清 ActiveStream,回到 Idle。
     */
    suspend fun stopStream(reason: String = "user stop")
}

internal enum class InviteState {
    Idle,
    Inviting,
    Streaming,
    Stopping,
}

internal sealed class InviteEvent {
    data class Started(val callId: String, val channelId: String) : InviteEvent()
    data class Stopped(val callId: String, val reason: String) : InviteEvent()
    data class Rejected(val statusCode: Int, val reason: String) : InviteEvent()
}

/**
 * 对外只读的 ActiveStream 快照。真实 ActiveStream data class 是 Impl 内部。
 * UI / 日志层通过这个 snapshot 观察当前流状态。
 */
internal data class ActiveStreamSnapshot(
    val callId: String,
    val channelId: String,
    val remoteHost: String,
    val remotePort: Int,
    val ssrc: String,
)

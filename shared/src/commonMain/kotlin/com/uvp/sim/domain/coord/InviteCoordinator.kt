package com.uvp.sim.domain.coord

import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 主叫推流(直播)对话域。
 *
 * 接管 SIP 流程(PR4 plan-tasks + PR5 收缩):
 * - INVITE(实时直播,SDP s=Play)+ ACK / BYE / CANCEL
 * - ActiveStream 状态(dialog / RTP 通道 / ACK 计时 / RTCP SR)
 *
 * PR5 T5.4 退出广播域 + 回放域:
 *   - 不再实现 BroadcastInvoker(改为 BroadcastCoordinator 实现)
 *   - 不处理 PLAYBACK / DOWNLOAD INVITE(SDP s=Playback 时 onIncoming 返回 Skip 让 Engine 路由给 Playback)
 *   - 不处理 INFO(全部 Skip)
 */
internal interface InviteCoordinator : Coordinator {
    val state: StateFlow<InviteState>
    val events: SharedFlow<InviteEvent>
    val activeStreamSnapshot: StateFlow<ActiveStreamSnapshot?>

    /** OSD 通道名 — 跟随被叫通道(前置 / 后置)。Engine 透出给渲染层。 */
    val currentChannelName: StateFlow<String>

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

/**
 * Coordinator 内部事件(13 类)。Engine 桥接逻辑翻译成 [com.uvp.sim.domain.SimEvent]。
 *
 * 设计原则:**类目按"路径"拆**(直播 / 回放 / 广播 handshake / 通用),
 * 不偷懒合并。每条 mapping 一行,Engine 桥接 `~80 行`。
 */
internal sealed class InviteEvent {
    // 直播
    data class IncomingInvite(val callId: String) : InviteEvent()
    data class StreamStarted(val callId: String, val remoteHost: String, val remotePort: Int, val ssrc: String) : InviteEvent()
    data class StreamStats(val callId: String, val frameCount: Int, val packetCount: Int) : InviteEvent()
    data class StreamStopped(val callId: String, val frameCount: Int, val packetCount: Int, val reason: String) : InviteEvent()
    data class CallEnded(val callId: String, val reason: String) : InviteEvent()
    data class AckTimeout(val callId: String) : InviteEvent()
    data class Rejected(val statusCode: Int, val reason: String) : InviteEvent()

    // 通用
    data class TransportError(val message: String) : InviteEvent()
    data class MessageSent(val message: SipMessage) : InviteEvent()
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

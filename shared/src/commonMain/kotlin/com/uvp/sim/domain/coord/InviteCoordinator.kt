package com.uvp.sim.domain.coord

import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 主叫推流(实时流 + 回放)对话域。
 *
 * 接管的 SIP 流程(plan 第 2.2 节 + PR4 plan-tasks):
 * - INVITE(实时流,SDP s= 缺省 / Play)+ INVITE(回放 / 下载,SDP s=Playback / Download)
 * - ACK / BYE / CANCEL / INFO(MANSRTSP)
 * - 200 OK 响应(主叫 broadcast / 设备发出去的 INVITE 响应)
 * - ActiveStream / ActivePlayback 状态(dialog / RTP 通道 / ACK 计时)
 *
 * 实现 [BroadcastInvoker] — ManscdpRouter 收到平台 MANSCDP Broadcast 命令时调本类
 * `fireBroadcastInvite`,本类发反向 INVITE。200 OK 后通过 [BroadcastDialogHandshakeListener]
 * 临时桥告诉 Engine 启动 RX 链(PR5 BroadcastCoordinator 拆出去后此桥废止)。
 */
internal interface InviteCoordinator : Coordinator, BroadcastInvoker {
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

    // 广播 handshake
    data class BroadcastInvited(val platformUri: String, val localAudioPort: Int) : InviteEvent()
    data class BroadcastHandshakeFailed(val reason: BroadcastEndReasonHint, val durationMs: Long) : InviteEvent()

    // 回放(PR4 临时:PR5 拆出去给 PlaybackCoordinator)
    data class PlaybackStarted(val callId: String, val ssrc: String, val isDownload: Boolean) : InviteEvent()
    data class PlaybackStopped(val callId: String, val reason: String) : InviteEvent()

    // 通用
    data class TransportError(val message: String) : InviteEvent()
    data class MessageSent(val message: SipMessage) : InviteEvent()
}

/**
 * 跨 Coord 共享枚举,避免 Invite 直接依赖 Engine 上的
 * [com.uvp.sim.domain.BroadcastEndReason](后者 UI / SimEvent 用)。
 * Engine 桥接时翻译:Error → Error,CodecRejected → CodecRejected,InviteFailed → InviteFailed。
 */
internal enum class BroadcastEndReasonHint { Error, CodecRejected, InviteFailed }

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

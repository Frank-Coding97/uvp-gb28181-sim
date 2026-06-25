package com.uvp.sim.domain.coord

import com.uvp.sim.domain.BroadcastDialog
import com.uvp.sim.domain.BroadcastDialogState
import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音对讲下行(BROADCAST INVITE / RX / 扬声器)对话域。
 *
 * PR5 完整抽离(决策 1-7):接管 RX 链 + dialog state + handshake +
 * 实现 [BroadcastInvoker]。Engine 退出广播域,只保留公开 API 委派。
 *
 * 接管的 SIP 流程:
 * - 发起 INVITE(实现 [BroadcastInvoker.fireBroadcastInvite],ManscdpRouter 收 MANSCDP Broadcast 触发)
 * - 200 OK / ACK / BYE
 * - RX 包接收 + 扬声器播放
 *
 * 来自 PR4 临时桥的迁移:engineBroadcastHandshakeListener 整段下沉本类,
 * BroadcastDialogHandshakeListener 接口删除。
 *
 * test-hook:`rxPacketCountForTest / decodeErrorCountForTest / isRxActive`
 *   → 改为 [debugSnapshot] 暴露。
 */
internal interface BroadcastCoordinator : Coordinator, BroadcastInvoker {
    val state: StateFlow<BroadcastDialogState>
    val current: StateFlow<BroadcastDialog?>
    val speakerOn: StateFlow<Boolean>
    val events: SharedFlow<BroadcastCoordEvent>

    fun setSpeaker(on: Boolean)
    suspend fun stop(reason: BroadcastEndReason = BroadcastEndReason.Local)

    /** 测试可见的内部诊断快照。生产代码不应依赖。 */
    fun debugSnapshot(): BroadcastDebugSnapshot
}

internal sealed class BroadcastCoordEvent {
    data class Invited(val platformUri: String, val localPort: Int) : BroadcastCoordEvent()
    data class Started(val firstPacketDelayMs: Long) : BroadcastCoordEvent()
    data class PacketRx(val rxPackets: Long, val rxBytes: Long, val codec: String) : BroadcastCoordEvent()
    data class Ended(val reason: BroadcastEndReason, val durationMs: Long) : BroadcastCoordEvent()
    data class TransportError(val message: String) : BroadcastCoordEvent()
    data class MessageSent(val message: SipMessage) : BroadcastCoordEvent()
}

internal data class BroadcastDebugSnapshot(
    val rxPacketCount: Long,
    val decodeErrorCount: Long,
    val rxActive: Boolean,
)

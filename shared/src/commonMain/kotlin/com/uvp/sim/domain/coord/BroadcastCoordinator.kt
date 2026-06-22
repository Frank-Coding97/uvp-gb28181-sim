package com.uvp.sim.domain.coord

import com.uvp.sim.domain.BroadcastDialog
import com.uvp.sim.domain.BroadcastDialogState
import com.uvp.sim.domain.BroadcastEndReason
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音对讲下行(BROADCAST INVITE / RX / 扬声器)对话域。
 *
 * 接管的 SIP 流程(plan 第 2.4 节):
 * - 发起 INVITE(由 ManscdpRouter 收到 MANSCDP Broadcast 触发,经 [BroadcastInvoker] 反向回调)
 * - 200 OK / ACK / BYE
 * - RX 包接收 + 扬声器播放
 *
 * 来自 SimulatorEngine 的方法迁移清单:handleBroadcast / sendBroadcastInvite /
 * handleBroadcastInviteResponse / sendBroadcastAck / sendBroadcastBye /
 * handleBroadcastBye / setBroadcastSpeaker
 *
 * test-hook 迁出:rxPacketCountForTest / decodeErrorCountForTest / isRxActive
 *   → 改为 [debugSnapshot] 暴露,test build 启用。
 */
internal interface BroadcastCoordinator : Coordinator {
    val state: StateFlow<BroadcastDialogState>
    val current: StateFlow<BroadcastDialog?>
    val events: SharedFlow<BroadcastCoordEvent>

    fun setSpeaker(on: Boolean)
    suspend fun stop(reason: String = "user stop")

    /** 测试可见的内部诊断快照。生产代码不应依赖。 */
    fun debugSnapshot(): BroadcastDebugSnapshot
}

internal sealed class BroadcastCoordEvent {
    data class Started(val callId: String, val sourceId: String, val targetId: String) : BroadcastCoordEvent()
    data class Ended(val reason: BroadcastEndReason, val durationMs: Long) : BroadcastCoordEvent()
}

internal data class BroadcastDebugSnapshot(
    val rxPacketCount: Long,
    val decodeErrorCount: Long,
    val rxActive: Boolean,
)

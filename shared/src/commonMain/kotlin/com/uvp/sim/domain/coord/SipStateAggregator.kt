package com.uvp.sim.domain.coord

import com.uvp.sim.domain.BroadcastDialogState
import com.uvp.sim.sip.SipState

/**
 * 把 5 个 Coordinator 的 state 聚合成 Engine 顶层的 [SipState]。纯函数,可单测。
 *
 * 聚合规则(plan 第 2.1.3 节):
 * - reg.Disconnected → SipState.Disconnected
 * - reg.Registering  → SipState.Registering
 * - reg.Failed       → SipState.Failed
 * - reg.Registered 且任一会话域在通话(Streaming/Inviting/Playing/Paused/Talking/Inviting)→ SipState.InCall
 * - 否则 → SipState.Registered
 *
 * 注:plan 列了 reg.RetryBackoff,但 SipState 没对应值,与 reg.Registering 同义(都还没 Registered)。
 */
internal fun aggregateSipState(
    reg: RegistrationState,
    inv: InviteState,
    pb: PlaybackState,
    bc: BroadcastDialogState?,
): SipState = when (reg) {
    RegistrationState.Disconnected -> SipState.Disconnected
    RegistrationState.Registering, RegistrationState.RetryBackoff -> SipState.Registering
    RegistrationState.Failed -> SipState.Failed
    RegistrationState.Registered -> when {
        inv == InviteState.Streaming || inv == InviteState.Inviting -> SipState.InCall
        pb == PlaybackState.Playing || pb == PlaybackState.Paused || pb == PlaybackState.Inviting -> SipState.InCall
        bc != null && bc != BroadcastDialogState.Failed -> SipState.InCall
        else -> SipState.Registered
    }
}

package com.uvp.sim.domain.coord

import com.uvp.sim.domain.BroadcastDialogState
import com.uvp.sim.sip.SipState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [aggregateSipState] 纯函数 11 用例覆盖关键聚合规则。
 *
 * 规则源:plan 第 2.1.3 节。
 * - reg 决定主轴(Disconnected / Registering / Failed / Registered)
 * - reg.Registered 时,任一会话域非 Idle 则 InCall
 */
class SipStateAggregatorTest {

    private val invIdle = InviteState.Idle
    private val pbIdle = PlaybackState.Idle
    private val bcIdle: BroadcastDialogState? = null

    @Test
    fun disconnected_when_reg_disconnected() {
        assertEquals(
            SipState.Disconnected,
            aggregateSipState(RegistrationState.Disconnected, invIdle, pbIdle, bcIdle),
        )
    }

    @Test
    fun registering_when_reg_registering_regardless_of_others() {
        assertEquals(
            SipState.Registering,
            aggregateSipState(
                RegistrationState.Registering,
                InviteState.Streaming,
                PlaybackState.Playing,
                BroadcastDialogState.Talking,
            ),
        )
    }

    @Test
    fun retryBackoff_maps_to_registering() {
        assertEquals(
            SipState.Registering,
            aggregateSipState(RegistrationState.RetryBackoff, invIdle, pbIdle, bcIdle),
        )
    }

    @Test
    fun failed_when_reg_failed() {
        assertEquals(
            SipState.Failed,
            aggregateSipState(RegistrationState.Failed, invIdle, pbIdle, bcIdle),
        )
    }

    @Test
    fun inCall_when_reg_registered_and_invite_streaming() {
        assertEquals(
            SipState.InCall,
            aggregateSipState(RegistrationState.Registered, InviteState.Streaming, pbIdle, bcIdle),
        )
    }

    @Test
    fun inCall_when_reg_registered_and_invite_inviting() {
        assertEquals(
            SipState.InCall,
            aggregateSipState(RegistrationState.Registered, InviteState.Inviting, pbIdle, bcIdle),
        )
    }

    @Test
    fun inCall_when_reg_registered_and_playback_playing() {
        assertEquals(
            SipState.InCall,
            aggregateSipState(RegistrationState.Registered, invIdle, PlaybackState.Playing, bcIdle),
        )
    }

    @Test
    fun inCall_when_reg_registered_and_playback_paused() {
        assertEquals(
            SipState.InCall,
            aggregateSipState(RegistrationState.Registered, invIdle, PlaybackState.Paused, bcIdle),
        )
    }

    @Test
    fun inCall_when_reg_registered_and_broadcast_talking() {
        assertEquals(
            SipState.InCall,
            aggregateSipState(RegistrationState.Registered, invIdle, pbIdle, BroadcastDialogState.Talking),
        )
    }

    @Test
    fun inCall_when_reg_registered_and_broadcast_inviting() {
        assertEquals(
            SipState.InCall,
            aggregateSipState(RegistrationState.Registered, invIdle, pbIdle, BroadcastDialogState.Inviting),
        )
    }

    @Test
    fun registered_when_reg_registered_and_broadcast_failed_treated_as_idle() {
        // BC 拿到 Failed 表示 dialog 终止清理中,不应聚合为 InCall
        assertEquals(
            SipState.Registered,
            aggregateSipState(RegistrationState.Registered, invIdle, pbIdle, BroadcastDialogState.Failed),
        )
    }

    @Test
    fun registered_when_reg_registered_and_all_others_idle() {
        assertEquals(
            SipState.Registered,
            aggregateSipState(RegistrationState.Registered, invIdle, pbIdle, bcIdle),
        )
    }
}

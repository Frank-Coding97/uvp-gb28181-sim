package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals

class SipStateMachineTest {

    @Test fun disconnected_register_goesToRegistering() {
        assertEquals(
            SipState.Registering,
            SipStateMachine.transition(SipState.Disconnected, SipEvent.RegisterRequested)
        )
    }

    @Test fun registering_200_goesToRegistered() {
        assertEquals(
            SipState.Registered,
            SipStateMachine.transition(SipState.Registering, SipEvent.Register200Received)
        )
    }

    @Test fun registering_401_staysInRegistering() {
        // 401 触发重发,状态保持
        assertEquals(
            SipState.Registering,
            SipStateMachine.transition(SipState.Registering,
                SipEvent.Register401Received("Digest realm=\"R\",nonce=\"N\""))
        )
    }

    @Test fun registering_failed_goesToFailed() {
        assertEquals(
            SipState.Failed,
            SipStateMachine.transition(SipState.Registering,
                SipEvent.RegisterFailed("403 Forbidden"))
        )
    }

    @Test fun registered_invite_goesToInCall() {
        assertEquals(
            SipState.InCall,
            SipStateMachine.transition(SipState.Registered, SipEvent.InviteReceived)
        )
    }

    @Test fun inCall_bye_goesToRegistered() {
        assertEquals(
            SipState.Registered,
            SipStateMachine.transition(SipState.InCall, SipEvent.ByeReceived)
        )
    }

    @Test fun registered_unregister_goesToDisconnected() {
        assertEquals(
            SipState.Disconnected,
            SipStateMachine.transition(SipState.Registered, SipEvent.UnregisterRequested)
        )
    }

    @Test fun failed_register_retry_goesToRegistering() {
        assertEquals(
            SipState.Registering,
            SipStateMachine.transition(SipState.Failed, SipEvent.RegisterRequested)
        )
    }

    @Test fun networkLost_anyState_goesToFailed() {
        for (s in listOf(SipState.Registering, SipState.Registered, SipState.InCall)) {
            assertEquals(
                SipState.Failed,
                SipStateMachine.transition(s, SipEvent.NetworkLost),
                "From $s on NetworkLost"
            )
        }
    }

    @Test fun illegalEventPreservesState() {
        // ByeReceived 在 Disconnected 不合法 — 应忽略,保持原状态
        assertEquals(
            SipState.Disconnected,
            SipStateMachine.transition(SipState.Disconnected, SipEvent.ByeReceived)
        )
        // 200 在 Registered 也不合法(我们应该忽略 stale 200)
        assertEquals(
            SipState.Registered,
            SipStateMachine.transition(SipState.Registered, SipEvent.Register200Received)
        )
    }

    @Test fun fullHappyPath() {
        var s = SipState.Disconnected
        s = SipStateMachine.transition(s, SipEvent.RegisterRequested)
        assertEquals(SipState.Registering, s)
        s = SipStateMachine.transition(s, SipEvent.Register200Received)
        assertEquals(SipState.Registered, s)
        s = SipStateMachine.transition(s, SipEvent.InviteReceived)
        assertEquals(SipState.InCall, s)
        s = SipStateMachine.transition(s, SipEvent.ByeReceived)
        assertEquals(SipState.Registered, s)
        s = SipStateMachine.transition(s, SipEvent.UnregisterRequested)
        assertEquals(SipState.Disconnected, s)
    }

    @Test fun authChallengeFlow() {
        // Disconnected -> Registering -> still Registering (got 401) -> Registered (got 200 after auth)
        var s = SipState.Disconnected
        s = SipStateMachine.transition(s, SipEvent.RegisterRequested)
        s = SipStateMachine.transition(s, SipEvent.Register401Received("Digest ..."))
        assertEquals(SipState.Registering, s)
        s = SipStateMachine.transition(s, SipEvent.Register200Received)
        assertEquals(SipState.Registered, s)
    }
}

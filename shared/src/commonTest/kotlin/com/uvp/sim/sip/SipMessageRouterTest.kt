package com.uvp.sim.sip

import com.uvp.sim.domain.BroadcastDialog
import com.uvp.sim.domain.BroadcastDialogState
import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.coord.ActiveStreamSnapshot
import com.uvp.sim.domain.coord.BroadcastCoordEvent
import com.uvp.sim.domain.coord.BroadcastCoordinator
import com.uvp.sim.domain.coord.BroadcastDebugSnapshot
import com.uvp.sim.domain.coord.InviteCoordinator
import com.uvp.sim.domain.coord.InviteEvent
import com.uvp.sim.domain.coord.InviteState
import com.uvp.sim.domain.coord.ManscdpEvent
import com.uvp.sim.domain.coord.ManscdpRouter
import com.uvp.sim.domain.coord.PlaybackCoordinator
import com.uvp.sim.domain.coord.PlaybackEvent
import com.uvp.sim.domain.coord.PlaybackState
import com.uvp.sim.domain.coord.RegistrationCoordinator
import com.uvp.sim.domain.coord.RegistrationEvent
import com.uvp.sim.domain.coord.RegistrationState
import com.uvp.sim.domain.coord.RoutingResult
import com.uvp.sim.gb28181.AlarmPayload
import io.ktor.client.HttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave 4 PR-D / P2-2:[SipMessageRouterImpl] 直接路径覆盖。
 *
 * 验证按 SIP method 路由到对应 Coord:RegisterCoord / InviteCoord / BroadcastCoord /
 * PlaybackCoord / ManscdpRouter,fallthrough 顺序 INVITE → playback / BYE → broadcast →
 * invite → playback / INFO → playback → manscdp。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SipMessageRouterTest {

    // ---- 5 个 RecorderCoord:记录 onIncoming 被调几次 + 传入的 method ----

    private class RecordingRegistration : RegistrationCoordinator {
        val calls = mutableListOf<SipMessage>()
        override val state: StateFlow<RegistrationState> = MutableStateFlow(RegistrationState.Registered)
        override val events: SharedFlow<RegistrationEvent> = MutableSharedFlow()
        override val clockOffset: StateFlow<ClockOffset> = MutableStateFlow(ClockOffset.Empty)
        override suspend fun register() {}
        override suspend fun cancelRegister() {}
        override suspend fun unregister() {}
        override suspend fun onIncoming(msg: SipMessage): RoutingResult {
            calls += msg
            return RoutingResult.Handled
        }
        override suspend fun shutdown() {}
    }

    private class RecordingInvite(
        private val skipBye: Boolean = true,
        private val skipInviteOnPlaybackSdp: Boolean = false,
    ) : InviteCoordinator {
        val calls = mutableListOf<SipMessage>()
        override val state: StateFlow<InviteState> = MutableStateFlow(InviteState.Idle)
        override val events: SharedFlow<InviteEvent> = MutableSharedFlow()
        override val activeStreamSnapshot: StateFlow<ActiveStreamSnapshot?> = MutableStateFlow(null)
        override val currentChannelName: StateFlow<String> = MutableStateFlow("default")
        override suspend fun stopStream(reason: String) {}
        override suspend fun onIncoming(msg: SipMessage): RoutingResult {
            calls += msg
            if (msg is SipRequest) {
                if (msg.method == SipMethod.INVITE && skipInviteOnPlaybackSdp) return RoutingResult.Skip
                if (msg.method == SipMethod.BYE && skipBye) return RoutingResult.Skip
            }
            return RoutingResult.Handled
        }
        override suspend fun shutdown() {}
    }

    private class RecordingBroadcast(
        private val skipBye: Boolean = true,
    ) : BroadcastCoordinator {
        val calls = mutableListOf<SipMessage>()
        override val state: StateFlow<BroadcastDialogState> = MutableStateFlow(BroadcastDialogState.Inviting)
        override val current: StateFlow<BroadcastDialog?> = MutableStateFlow(null)
        override val speakerOn: StateFlow<Boolean> = MutableStateFlow(true)
        override val events: SharedFlow<BroadcastCoordEvent> = MutableSharedFlow()
        override fun setSpeaker(on: Boolean) {}
        override suspend fun stop(reason: BroadcastEndReason) {}
        override fun debugSnapshot(): BroadcastDebugSnapshot = BroadcastDebugSnapshot(0, 0, false)
        override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) {}
        override suspend fun onIncoming(msg: SipMessage): RoutingResult {
            calls += msg
            if (msg is SipRequest && msg.method == SipMethod.BYE && skipBye) return RoutingResult.Skip
            return RoutingResult.Handled
        }
        override suspend fun shutdown() {}
    }

    private class RecordingPlayback(
        private val skipInfo: Boolean = true,
        private val skipBye: Boolean = false,
    ) : PlaybackCoordinator {
        val calls = mutableListOf<SipMessage>()
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
        override val events: SharedFlow<PlaybackEvent> = MutableSharedFlow()
        override suspend fun stop(reason: String) {}
        override suspend fun onIncoming(msg: SipMessage): RoutingResult {
            calls += msg
            if (msg is SipRequest) {
                if (msg.method == SipMethod.INFO && skipInfo) return RoutingResult.Skip
                if (msg.method == SipMethod.BYE && skipBye) return RoutingResult.Skip
            }
            return RoutingResult.Handled
        }
        override suspend fun shutdown() {}
    }

    private class RecordingManscdp : ManscdpRouter {
        val calls = mutableListOf<SipMessage>()
        override val deviceControlState: StateFlow<DeviceControlModel> =
            MutableStateFlow(DeviceControlModel())
        override val events: SharedFlow<ManscdpEvent> = MutableSharedFlow()
        override suspend fun reportSnapshot() {}
        override suspend fun reportAlarm(payload: AlarmPayload) {}
        override suspend fun localResetAlarm() {}
        override suspend fun triggerMediaStatusAbnormal(notifyType: Int) {}
        override fun attachSnapshotPipeline(
            capture: com.uvp.sim.snapshot.SnapshotCapture,
            cache: com.uvp.sim.snapshot.JpegLocalCache,
            httpClient: HttpClient,
        ) {}
        override suspend fun onIncoming(msg: SipMessage): RoutingResult {
            calls += msg
            return RoutingResult.Handled
        }
        override suspend fun shutdown() {}
    }

    private fun request(method: SipMethod, callId: String = "abc@host"): SipRequest = SipRequest(
        method = method,
        requestUri = "sip:device@host",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 1.1.1.1:5060;branch=z9hG4bKtest"),
            SipMessage.Header(SipHeader.FROM, "<sip:from@host>;tag=ft"),
            SipMessage.Header(SipHeader.TO, "<sip:to@host>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 ${method.name}"),
        ),
    )

    private fun response(statusCode: Int, cseqMethod: SipMethod): SipResponse = SipResponse(
        statusCode = statusCode,
        reasonPhrase = "OK",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 1.1.1.1:5060;branch=z9hG4bKtest"),
            SipMessage.Header(SipHeader.FROM, "<sip:from@host>;tag=ft"),
            SipMessage.Header(SipHeader.TO, "<sip:to@host>;tag=tt"),
            SipMessage.Header(SipHeader.CALL_ID, "resp@host"),
            SipMessage.Header(SipHeader.CSEQ, "1 ${cseqMethod.name}"),
        ),
    )

    @Test
    fun register_request_options_routes_to_registration() = runTest {
        val reg = RecordingRegistration()
        val router = SipMessageRouterImpl(reg, RecordingInvite(), RecordingBroadcast(), RecordingPlayback(), RecordingManscdp())

        router.route(request(SipMethod.OPTIONS))
        runCurrent()
        assertEquals(1, reg.calls.size)
        assertTrue((reg.calls.first() as SipRequest).method == SipMethod.OPTIONS)
    }

    @Test
    fun message_request_routes_to_manscdp() = runTest {
        val mans = RecordingManscdp()
        val router = SipMessageRouterImpl(RecordingRegistration(), RecordingInvite(), RecordingBroadcast(), RecordingPlayback(), mans)

        router.route(request(SipMethod.MESSAGE))
        runCurrent()
        assertEquals(1, mans.calls.size)
    }

    @Test
    fun subscribe_request_routes_to_manscdp() = runTest {
        val mans = RecordingManscdp()
        val router = SipMessageRouterImpl(RecordingRegistration(), RecordingInvite(), RecordingBroadcast(), RecordingPlayback(), mans)

        router.route(request(SipMethod.SUBSCRIBE))
        runCurrent()
        assertEquals(1, mans.calls.size)
    }

    @Test
    fun invite_request_tries_invite_then_falls_through_to_playback() = runTest {
        val invite = RecordingInvite(skipInviteOnPlaybackSdp = true)  // 让 INVITE 落到 playback
        val playback = RecordingPlayback()
        val router = SipMessageRouterImpl(RecordingRegistration(), invite, RecordingBroadcast(), playback, RecordingManscdp())

        router.route(request(SipMethod.INVITE))
        runCurrent()

        assertEquals(1, invite.calls.size, "invite 先吃")
        assertEquals(1, playback.calls.size, "invite skip 后 fallthrough 到 playback")
    }

    @Test
    fun invite_request_stays_with_invite_when_handled() = runTest {
        val invite = RecordingInvite(skipInviteOnPlaybackSdp = false)  // invite 自己吃下
        val playback = RecordingPlayback()
        val router = SipMessageRouterImpl(RecordingRegistration(), invite, RecordingBroadcast(), playback, RecordingManscdp())

        router.route(request(SipMethod.INVITE))
        runCurrent()

        assertEquals(1, invite.calls.size)
        assertEquals(0, playback.calls.size, "invite Handled 后不再 fallthrough")
    }

    @Test
    fun ack_routes_to_invite_only() = runTest {
        val invite = RecordingInvite()
        val playback = RecordingPlayback()
        val router = SipMessageRouterImpl(RecordingRegistration(), invite, RecordingBroadcast(), playback, RecordingManscdp())

        router.route(request(SipMethod.ACK))
        runCurrent()
        assertEquals(1, invite.calls.size)
        assertEquals(0, playback.calls.size)
    }

    @Test
    fun bye_fallthrough_broadcast_invite_playback() = runTest {
        val broadcast = RecordingBroadcast(skipBye = true)
        val invite = RecordingInvite(skipBye = true)
        val playback = RecordingPlayback(skipBye = false)
        val router = SipMessageRouterImpl(RecordingRegistration(), invite, broadcast, playback, RecordingManscdp())

        router.route(request(SipMethod.BYE))
        runCurrent()

        assertEquals(1, broadcast.calls.size, "BYE 先问 broadcast")
        assertEquals(1, invite.calls.size, "broadcast skip 后试 invite")
        assertEquals(1, playback.calls.size, "invite 也 skip 后试 playback")
    }

    @Test
    fun info_request_tries_playback_then_manscdp() = runTest {
        val playback = RecordingPlayback(skipInfo = true)
        val mans = RecordingManscdp()
        val router = SipMessageRouterImpl(RecordingRegistration(), RecordingInvite(), RecordingBroadcast(), playback, mans)

        router.route(request(SipMethod.INFO))
        runCurrent()

        assertEquals(1, playback.calls.size, "playback 先试")
        assertEquals(1, mans.calls.size, "playback skip 后 fallthrough 到 manscdp")
    }

    @Test
    fun register_response_routes_to_registration_and_triggers_state_callback() = runTest {
        val reg = RecordingRegistration()
        var stateChanged = 0
        val router = SipMessageRouterImpl(
            reg, RecordingInvite(), RecordingBroadcast(), RecordingPlayback(), RecordingManscdp(),
            onRegistrationStateChanged = { stateChanged += 1 },
        )

        router.route(response(200, SipMethod.REGISTER))
        runCurrent()

        assertEquals(1, reg.calls.size)
        assertEquals(1, stateChanged, "REGISTER 2xx 应触发 state sync callback")
    }

    @Test
    fun message_2xx_response_routes_to_registration_and_triggers_ack_callback() = runTest {
        val reg = RecordingRegistration()
        var ack = 0
        val router = SipMessageRouterImpl(
            reg, RecordingInvite(), RecordingBroadcast(), RecordingPlayback(), RecordingManscdp(),
            onMessage2xxAck = { ack += 1 },
        )

        router.route(response(200, SipMethod.MESSAGE))
        runCurrent()

        assertEquals(1, reg.calls.size)
        assertEquals(1, ack, "MESSAGE 2xx 应触发 heartbeat ack callback")
    }

    @Test
    fun message_non_2xx_response_not_routed() = runTest {
        val reg = RecordingRegistration()
        var ack = 0
        val router = SipMessageRouterImpl(
            reg, RecordingInvite(), RecordingBroadcast(), RecordingPlayback(), RecordingManscdp(),
            onMessage2xxAck = { ack += 1 },
        )

        router.route(response(404, SipMethod.MESSAGE))
        runCurrent()

        assertEquals(0, reg.calls.size, "MESSAGE 4xx 不应路由到 registration")
        assertEquals(0, ack)
    }

    @Test
    fun invite_response_routes_to_broadcast() = runTest {
        val broadcast = RecordingBroadcast()
        val router = SipMessageRouterImpl(
            RecordingRegistration(), RecordingInvite(), broadcast, RecordingPlayback(), RecordingManscdp()
        )

        router.route(response(200, SipMethod.INVITE))
        runCurrent()

        assertEquals(1, broadcast.calls.size, "INVITE 2xx 路由到 broadcast(主叫 Broadcast INVITE 200)")
    }
}

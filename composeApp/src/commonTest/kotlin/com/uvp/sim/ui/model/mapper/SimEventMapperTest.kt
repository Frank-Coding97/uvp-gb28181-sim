package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.gb28181.AlarmPriority
import com.uvp.sim.gb28181.AlarmType
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.ui.model.AlarmPriorityDto
import com.uvp.sim.ui.model.AlarmTypeDto
import com.uvp.sim.ui.model.BroadcastEndReasonDto
import com.uvp.sim.ui.model.ResetSourceDto
import com.uvp.sim.ui.model.SimEventDto
import com.uvp.sim.ui.model.SipMessageDto
import com.uvp.sim.ui.model.SipMethodDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SimEventMapperTest {

    // ---- Registration ----
    @Test
    fun registrationStarted_maps_server_and_timestamp() {
        val dto = SimEvent.RegistrationStarted("8.8.8.8", 1000L).toDto()
        assertIs<SimEventDto.RegistrationStarted>(dto)
        assertEquals("8.8.8.8", dto.server)
        assertEquals(1000L, dto.timestampMs)
    }

    @Test
    fun registrationChallenged_preserves_nonce() {
        val dto = SimEvent.RegistrationChallenged("abc123", 2000L).toDto()
        assertIs<SimEventDto.RegistrationChallenged>(dto)
        assertEquals("abc123", dto.nonce)
    }

    @Test
    fun registrationFailed_preserves_reason() {
        val dto = SimEvent.RegistrationFailed("Timeout", 3000L).toDto()
        assertIs<SimEventDto.RegistrationFailed>(dto)
        assertEquals("Timeout", dto.reason)
    }

    @Test
    fun registrationRetryScheduled_preserves_delay_and_attempt() {
        val dto = SimEvent.RegistrationRetryScheduled(5000L, 3, 4000L).toDto()
        assertIs<SimEventDto.RegistrationRetryScheduled>(dto)
        assertEquals(5000L, dto.delayMs)
        assertEquals(3, dto.attempt)
    }

    // ---- Heartbeat ----
    @Test
    fun heartbeatSent_preserves_sequence() {
        val dto = SimEvent.HeartbeatSent(42, 5000L).toDto()
        assertIs<SimEventDto.HeartbeatSent>(dto)
        assertEquals(42, dto.sequence)
    }

    @Test
    fun heartbeatTimeoutDetected_preserves_counters() {
        val dto = SimEvent.HeartbeatTimeoutDetected(3, 5, 6000L).toDto()
        assertIs<SimEventDto.HeartbeatTimeoutDetected>(dto)
        assertEquals(3, dto.missedCount)
        assertEquals(5, dto.maxAllowed)
    }

    // ---- Stream ----
    @Test
    fun streamStarted_preserves_fields() {
        val dto = SimEvent.StreamStarted("call-1", "8.8.8.8", 30000, "ssrc-1", 7000L).toDto()
        assertIs<SimEventDto.StreamStarted>(dto)
        assertEquals("call-1", dto.callId)
        assertEquals(30000, dto.remotePort)
        assertEquals("ssrc-1", dto.ssrc)
    }

    @Test
    fun streamStopped_preserves_counts_and_reason() {
        val dto = SimEvent.StreamStopped("call-1", 100, 200, "BYE", 8000L).toDto()
        assertIs<SimEventDto.StreamStopped>(dto)
        assertEquals(100, dto.frameCount)
        assertEquals(200, dto.packetCount)
        assertEquals("BYE", dto.reason)
    }

    // ---- SIP message nested ----
    @Test
    fun messageSent_nests_sipMessage_dto_recursively() {
        val sipReq = SipRequest(
            method = SipMethod.REGISTER,
            requestUri = "sip:test",
            headers = listOf(SipMessage.Header("From", "alice")),
        )
        val dto = SimEvent.MessageSent(sipReq, 9000L).toDto()
        assertIs<SimEventDto.MessageSent>(dto)
        assertIs<SipMessageDto.Request>(dto.message)
        assertEquals(SipMethodDto.REGISTER, (dto.message as SipMessageDto.Request).method)
    }

    @Test
    fun messageReceived_nests_sipMessage_dto() {
        val sipReq = SipRequest(SipMethod.MESSAGE, "sip:test", headers = emptyList())
        val dto = SimEvent.MessageReceived(sipReq, 10000L).toDto()
        assertIs<SimEventDto.MessageReceived>(dto)
        assertIs<SipMessageDto.Request>(dto.message)
    }

    // ---- Subscribe ----
    @Test
    fun subscribeReceived_preserves_fields() {
        val dto = SimEvent.SubscribeReceived("sub-1", "Catalog", 3600, 60, 11000L).toDto()
        assertIs<SimEventDto.SubscribeReceived>(dto)
        assertEquals("sub-1", dto.subscriber)
        assertEquals("Catalog", dto.kind)
        assertEquals(3600, dto.expiresSeconds)
        assertEquals(60, dto.intervalSeconds)
    }

    // ---- Alarm ----
    @Test
    fun alarmFired_maps_nested_type_and_priority() {
        val dto = SimEvent.AlarmFired(
            type = AlarmType.VideoLost,
            priority = AlarmPriority.EmergencyL1,
            description = "test",
            timestampMs = 12000L,
        ).toDto()
        assertIs<SimEventDto.AlarmFired>(dto)
        assertEquals(AlarmTypeDto.VideoLost, dto.type)
        assertEquals(AlarmPriorityDto.EmergencyL1, dto.priority)
        assertEquals("test", dto.description)
    }

    @Test
    fun alarmReset_local_maps_to_dto_local() {
        val dto = SimEvent.AlarmReset(SimEvent.ResetSource.Local, 13000L).toDto()
        assertIs<SimEventDto.AlarmReset>(dto)
        assertEquals(ResetSourceDto.Local, dto.by)
    }

    @Test
    fun alarmReset_remote_preserves_subscriber() {
        val dto = SimEvent.AlarmReset(SimEvent.ResetSource.Remote("sub-1"), 14000L).toDto()
        val by = (dto as SimEventDto.AlarmReset).by
        assertIs<ResetSourceDto.Remote>(by)
        assertEquals("sub-1", by.subscriber)
    }

    // ---- Broadcast ----
    @Test
    fun broadcastEnded_maps_end_reason() {
        BroadcastEndReason.entries.forEach { reason ->
            val dto = SimEvent.BroadcastEnded(reason, 1000L, 15000L).toDto()
            assertIs<SimEventDto.BroadcastEnded>(dto)
            assertEquals(reason.name, dto.reason.name)
        }
        assertEquals(BroadcastEndReason.entries.size, BroadcastEndReasonDto.entries.size)
    }

    @Test
    fun broadcastPacketRx_preserves_stats() {
        val dto = SimEvent.BroadcastPacketRx(100L, 50000L, "G.711", 16000L).toDto()
        assertIs<SimEventDto.BroadcastPacketRx>(dto)
        assertEquals(100L, dto.rxPackets)
        assertEquals(50000L, dto.rxBytes)
        assertEquals("G.711", dto.codec)
    }

    // ---- Network ----
    @Test
    fun networkBound_preserves_fields() {
        val dto = SimEvent.NetworkBound("WIFI", "wlan0", "192.168.1.1", 17000L).toDto()
        assertIs<SimEventDto.NetworkBound>(dto)
        assertEquals("WIFI", dto.preference)
        assertEquals("wlan0", dto.interfaceName)
        assertEquals("192.168.1.1", dto.localIp)
    }

    @Test
    fun networkUnavailable_preserves_reason() {
        val dto = SimEvent.NetworkUnavailable("No SIM", 18000L).toDto()
        assertIs<SimEventDto.NetworkUnavailable>(dto)
        assertEquals("No SIM", dto.reason)
    }

    @Test
    fun networkAuto_maps_to_dto_object() {
        assertEquals(SimEventDto.NetworkAuto, SimEvent.NetworkAuto.toDto())
    }

    // ---- Transport / Misc ----
    @Test
    fun transportError_preserves_description() {
        val dto = SimEvent.TransportError("Socket closed", 19000L).toDto()
        assertIs<SimEventDto.TransportError>(dto)
        assertEquals("Socket closed", dto.description)
    }

    @Test
    fun deviceControlReceived_preserves_command_and_detail() {
        val dto = SimEvent.DeviceControlReceived("PTZCmd", "0xDEADBEEF", 20000L).toDto()
        assertIs<SimEventDto.DeviceControlReceived>(dto)
        assertEquals("PTZCmd", dto.commandType)
        assertEquals("0xDEADBEEF", dto.detail)
    }
}

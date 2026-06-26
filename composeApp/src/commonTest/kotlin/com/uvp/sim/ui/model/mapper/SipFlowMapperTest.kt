package com.uvp.sim.ui.model.mapper

import com.uvp.sim.observability.DialogRow
import com.uvp.sim.observability.FlowItem
import com.uvp.sim.observability.SipFlowEvent
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.ui.model.DialogRowDto
import com.uvp.sim.ui.model.FlowItemDto
import com.uvp.sim.ui.model.SipMessageDto
import com.uvp.sim.ui.model.SipMethodDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SipFlowMapperTest {

    private fun sampleReq() = SipRequest(SipMethod.REGISTER, "sip:test", headers = emptyList())

    @Test
    fun sipFlowEvent_nests_sipMessage_dto() {
        val dto = SipFlowEvent(1000L, outgoing = true, message = sampleReq(), callId = "c-1").toDto()
        assertEquals(1000L, dto.timestampMs)
        assertEquals(true, dto.outgoing)
        assertEquals("c-1", dto.callId)
        assertIs<SipMessageDto.Request>(dto.message)
        assertEquals(SipMethodDto.REGISTER, (dto.message as SipMessageDto.Request).method)
    }

    @Test
    fun dialogRow_message_preserves_fields_and_nests_sipMessage() {
        val dto = DialogRow.Message(1000L, false, "title", "summary", sampleReq()).toDto()
        assertIs<DialogRowDto.Message>(dto)
        assertEquals("title", dto.title)
        assertEquals("summary", dto.summary)
        assertIs<SipMessageDto.Request>(dto.rawMessage)
    }

    @Test
    fun dialogRow_mediaSegment_preserves_all_fields() {
        val dto = DialogRow.MediaSegment(1000L, 2000L, 100, 200, "c-1", "8.8.8.8", 30000).toDto()
        assertIs<DialogRowDto.MediaSegment>(dto)
        assertEquals(1000L, dto.startedAtMs)
        assertEquals(2000L, dto.stoppedAtMs)
        assertEquals(100, dto.frameCount)
        assertEquals("8.8.8.8", dto.remoteHost)
    }

    @Test
    fun flowItem_dialog_recurses_rows() {
        val row = DialogRow.Message(1000L, false, "t", "s", sampleReq())
        val dto = FlowItem.Dialog("c-1", 1000L, listOf(row)).toDto()
        assertIs<FlowItemDto.Dialog>(dto)
        assertEquals(1, dto.rows.size)
        assertIs<DialogRowDto.Message>(dto.rows[0])
    }

    @Test
    fun flowItem_heartbeatCluster_filters_to_message_only() {
        val msgRow = DialogRow.Message(1000L, true, "t", "s", sampleReq())
        val dto = FlowItem.HeartbeatCluster(1000L, 2000L, 5, listOf(msgRow)).toDto()
        assertIs<FlowItemDto.HeartbeatCluster>(dto)
        assertEquals(5, dto.count)
        assertEquals(1, dto.rows.size)
    }
}

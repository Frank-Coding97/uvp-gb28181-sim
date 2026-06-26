package com.uvp.sim.ui.model.mapper

import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import com.uvp.sim.ui.model.SipMessageDto
import com.uvp.sim.ui.model.SipMethodDto
import com.uvp.sim.ui.model.SipStateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SipMapperTest {

    @Test
    fun sipRequest_toDto_preserves_method_uri_headers_and_decodes_body() {
        val domain = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:34020000@8.8.8.8:5060",
            headers = listOf(SipMessage.Header("From", "alice")),
            body = "v=0\r\no=- 0 0".encodeToByteArray(),
        )
        val dto = domain.toDto()
        assertIs<SipMessageDto.Request>(dto)
        assertEquals(SipMethodDto.INVITE, dto.method)
        assertEquals("sip:34020000@8.8.8.8:5060", dto.requestUri)
        assertEquals(listOf(SipMessageDto.Header("From", "alice")), dto.headers)
        assertEquals("v=0\r\no=- 0 0", dto.body)
        assertEquals("SIP/2.0", dto.sipVersion)
    }

    @Test
    fun sipResponse_toDto_preserves_status_reason_headers_and_decodes_body() {
        val domain = SipResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = listOf(SipMessage.Header("CSeq", "42 REGISTER")),
            body = ByteArray(0),
        )
        val dto = domain.toDto()
        assertIs<SipMessageDto.Response>(dto)
        assertEquals(200, dto.statusCode)
        assertEquals("OK", dto.reasonPhrase)
        assertEquals(listOf(SipMessageDto.Header("CSeq", "42 REGISTER")), dto.headers)
        assertEquals("", dto.body)
    }

    @Test
    fun sipMethod_all_entries_map_to_dto_by_name() {
        SipMethod.entries.forEach { method ->
            assertEquals(method.name, method.toDto().name)
        }
        // DTO 跟 domain enum 数量必须一致, 否则 valueOf 会爆
        assertEquals(SipMethod.entries.size, SipMethodDto.entries.size)
    }

    @Test
    fun sipState_all_entries_map_to_dto_by_name() {
        SipState.entries.forEach { state ->
            assertEquals(state.name, state.toDto().name)
        }
        assertEquals(SipState.entries.size, SipStateDto.entries.size)
    }
}

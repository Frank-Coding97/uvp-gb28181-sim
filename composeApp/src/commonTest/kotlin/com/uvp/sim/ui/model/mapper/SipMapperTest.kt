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

    // ──────────────────────────────────────────────────────────────
    // P2-7:SipMapper 应对 Authorization 系列头生成 redactedHeaders
    // ──────────────────────────────────────────────────────────────

    @Test
    fun toDto_redactsAuthorizationHeader() {
        val domain = SipRequest(
            method = SipMethod.REGISTER,
            requestUri = "sip:34020000001320000001@10.0.0.1",
            headers = listOf(
                SipMessage.Header("From", "<sip:34020000001310000001@10.0.0.1>"),
                SipMessage.Header("Authorization", "Digest username=\"34020000001310000001\", nonce=\"abc123\", response=\"dead00beef\""),
                SipMessage.Header("CSeq", "1 REGISTER"),
            ),
            body = ByteArray(0),
        )
        val dto = domain.toDto()
        // .headers 保留原值(业务路径可能需要)
        assertEquals(3, dto.headers.size)
        assertEquals("Digest username=\"34020000001310000001\", nonce=\"abc123\", response=\"dead00beef\"",
            dto.headers[1].value)

        // .redactedHeaders 脱敏
        assertEquals(3, dto.redactedHeaders.size)
        assertEquals("<redacted>", dto.redactedHeaders[1].value, "Authorization 应被脱敏")
        // 非敏感头不动
        assertEquals("<sip:34020000001310000001@10.0.0.1>", dto.redactedHeaders[0].value)
        assertEquals("1 REGISTER", dto.redactedHeaders[2].value)
    }

    @Test
    fun toDto_redactsProxyAuthorizationHeader() {
        val domain = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:x@y",
            headers = listOf(
                SipMessage.Header("Proxy-Authorization", "Digest username=\"alice\", response=\"secret\""),
            ),
            body = ByteArray(0),
        )
        val dto = domain.toDto()
        assertEquals("Digest username=\"alice\", response=\"secret\"", dto.headers[0].value)
        assertEquals("<redacted>", dto.redactedHeaders[0].value)
    }

    @Test
    fun toDto_redactsWWWAuthenticateHeader() {
        val domain = SipResponse(
            statusCode = 401,
            reasonPhrase = "Unauthorized",
            headers = listOf(
                SipMessage.Header("WWW-Authenticate", "Digest realm=\"3402000000\", nonce=\"ffffeeee\""),
            ),
            body = ByteArray(0),
        )
        val dto = domain.toDto()
        assertEquals("Digest realm=\"3402000000\", nonce=\"ffffeeee\"", dto.headers[0].value)
        assertEquals("<redacted>", dto.redactedHeaders[0].value)
    }

    @Test
    fun toDto_redactsProxyAuthenticateHeader() {
        val domain = SipResponse(
            statusCode = 407,
            reasonPhrase = "Proxy Authentication Required",
            headers = listOf(
                SipMessage.Header("Proxy-Authenticate", "Basic realm=\"proxy\""),
            ),
            body = ByteArray(0),
        )
        val dto = domain.toDto()
        assertEquals("Basic realm=\"proxy\"", dto.headers[0].value)
        assertEquals("<redacted>", dto.redactedHeaders[0].value)
    }

    @Test
    fun toDto_caseInsensitiveRedaction() {
        val domain = SipRequest(
            method = SipMethod.REGISTER,
            requestUri = "sip:x@y",
            headers = listOf(
                SipMessage.Header("AUTHORIZATION", "Digest creds"),
                SipMessage.Header("authorization", "Digest creds2"),
                SipMessage.Header("AuThOrIzAtIoN", "Digest creds3"),
            ),
            body = ByteArray(0),
        )
        val dto = domain.toDto()
        // 全部脱敏
        assertEquals("<redacted>", dto.redactedHeaders[0].value)
        assertEquals("<redacted>", dto.redactedHeaders[1].value)
        assertEquals("<redacted>", dto.redactedHeaders[2].value)
    }

    @Test
    fun toDto_nonSensitiveHeadersUntouched() {
        val domain = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:x@y",
            headers = listOf(
                SipMessage.Header("From", "<sip:a@b>"),
                SipMessage.Header("To", "<sip:c@d>"),
                SipMessage.Header("CSeq", "1 INVITE"),
                SipMessage.Header("Contact", "<sip:e@f>"),
            ),
            body = ByteArray(0),
        )
        val dto = domain.toDto()
        // headers 和 redactedHeaders 应该完全相同(无敏感头)
        assertEquals(dto.headers, dto.redactedHeaders)
    }

    @Test
    fun toDto_mixedSensitiveAndNonSensitive() {
        val domain = SipRequest(
            method = SipMethod.REGISTER,
            requestUri = "sip:x@y",
            headers = listOf(
                SipMessage.Header("From", "<sip:a@b>"),
                SipMessage.Header("Authorization", "Digest nonce=\"abc\""),
                SipMessage.Header("CSeq", "2 REGISTER"),
                SipMessage.Header("Proxy-Authorization", "Digest response=\"xyz\""),
                SipMessage.Header("To", "<sip:c@d>"),
            ),
            body = ByteArray(0),
        )
        val dto = domain.toDto()
        // headers 全保留原值
        assertEquals(5, dto.headers.size)
        assertEquals("Digest nonce=\"abc\"", dto.headers[1].value)
        assertEquals("Digest response=\"xyz\"", dto.headers[3].value)

        // redactedHeaders 中敏感头脱敏,非敏感头不变
        assertEquals(5, dto.redactedHeaders.size)
        assertEquals("<sip:a@b>", dto.redactedHeaders[0].value)
        assertEquals("<redacted>", dto.redactedHeaders[1].value)
        assertEquals("2 REGISTER", dto.redactedHeaders[2].value)
        assertEquals("<redacted>", dto.redactedHeaders[3].value)
        assertEquals("<sip:c@d>", dto.redactedHeaders[4].value)
    }
}

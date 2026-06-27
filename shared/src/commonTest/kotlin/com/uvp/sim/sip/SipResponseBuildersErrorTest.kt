package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1-3 — SipResponseBuilders.buildSimpleError 必须带 Date 头(GB §4.15 时钟切面).
 * 4xx/5xx 响应跟 2xx 一样要 inject Date,否则上游平台时钟校验会失败.
 */
class SipResponseBuildersErrorTest {

    private fun fakeInvite(): SipRequest = SipRequest(
        method = SipMethod.INVITE,
        requestUri = "sip:34020000001110000001@192.168.10.50:5060",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/UDP 192.168.10.100:5060;rport;branch=z9hG4bK-err-1"),
            SipMessage.Header(SipHeader.FROM,
                "<sip:34020000002000000001@3402000000>;tag=plat-err-tag"),
            SipMessage.Header(SipHeader.TO,
                "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, "err-call-id@platform"),
            SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
            SipMessage.Header(SipHeader.MAX_FORWARDS, "70")
        )
    )

    @Test fun buildSimpleError_403_carries_date_header() {
        val resp = SipResponseBuilders.buildSimpleError(fakeInvite(), 403, "Forbidden")
        assertEquals(403, resp.statusCode)
        val date = resp.headers.firstOrNull { SipHeader.canonicalize(it.name) == SipHeader.DATE }
        assertTrue(date != null, "403 response must include Date header (GB §4.15)")
        assertTrue(date!!.value.contains("GMT"), "Date should be RFC 1123 GMT format, was: ${date.value}")
    }

    @Test fun buildSimpleError_488_carries_date_header() {
        val resp = SipResponseBuilders.buildSimpleError(fakeInvite(), 488, "Not Acceptable Here")
        val date = resp.headers.firstOrNull { SipHeader.canonicalize(it.name) == SipHeader.DATE }
        assertTrue(date != null, "488 response must include Date header")
    }

    @Test fun buildSimpleError_500_carries_date_header() {
        val resp = SipResponseBuilders.buildSimpleError(fakeInvite(), 500, "Server Internal Error")
        val date = resp.headers.firstOrNull { SipHeader.canonicalize(it.name) == SipHeader.DATE }
        assertTrue(date != null, "5xx response must include Date header")
    }

    @Test fun buildSimpleError_preserves_via_from_callid_cseq() {
        val resp = SipResponseBuilders.buildSimpleError(fakeInvite(), 486, "Busy Here")
        val names = resp.headers.map { SipHeader.canonicalize(it.name) }.toSet()
        assertTrue(SipHeader.VIA in names)
        assertTrue(SipHeader.FROM in names)
        assertTrue(SipHeader.CALL_ID in names)
        assertTrue(SipHeader.CSEQ in names)
        assertTrue(SipHeader.TO in names)
        assertTrue(SipHeader.DATE in names)
    }
}

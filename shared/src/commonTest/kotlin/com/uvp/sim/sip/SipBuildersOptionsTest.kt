package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A1 — SipBuilders.buildOptionsResponse 单测(M5 平台兼容性补漏 batch1).
 * 200 OK + Allow 头(列出真实支持方法)+ Date + 可选 User-Agent.
 */
class SipBuildersOptionsTest {

    private fun fakeOptions(): SipRequest = SipRequest(
        method = SipMethod.OPTIONS,
        requestUri = "sip:34020000001110000001@192.168.10.50:5060",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/UDP 192.168.10.100:5060;rport;branch=z9hG4bK-opt-1"),
            SipMessage.Header(SipHeader.FROM,
                "<sip:34020000002000000001@3402000000>;tag=plat-tag"),
            SipMessage.Header(SipHeader.TO,
                "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, "options-call-id@platform"),
            SipMessage.Header(SipHeader.CSEQ, "42 OPTIONS"),
            SipMessage.Header(SipHeader.MAX_FORWARDS, "70")
        )
    )

    @Test fun a1_t1_statusCode200() {
        val resp = SipBuilders.buildOptionsResponse(
            request = fakeOptions(),
            allowedMethods = listOf(SipMethod.INVITE, SipMethod.BYE)
        )
        assertEquals(200, resp.statusCode)
        assertEquals("OK", resp.reasonPhrase)
    }

    @Test fun a1_t2_viaFromCallIdCseqEchoed() {
        val req = fakeOptions()
        val resp = SipBuilders.buildOptionsResponse(
            request = req,
            allowedMethods = listOf(SipMethod.OPTIONS)
        )
        assertEquals(req.firstHeader(SipHeader.VIA), resp.firstHeader(SipHeader.VIA))
        assertEquals(req.firstHeader(SipHeader.FROM), resp.firstHeader(SipHeader.FROM))
        assertEquals(req.firstHeader(SipHeader.CALL_ID), resp.firstHeader(SipHeader.CALL_ID))
        assertEquals(req.firstHeader(SipHeader.CSEQ), resp.firstHeader(SipHeader.CSEQ))
        // To 透传(可被附 tag,但 value 至少包含原 URI 子串)
        val to = resp.firstHeader(SipHeader.TO)
        assertNotNull(to)
        assertTrue(to.contains("34020000001110000001"))
    }

    @Test fun a1_t3_allowHeaderPresent() {
        val resp = SipBuilders.buildOptionsResponse(
            request = fakeOptions(),
            allowedMethods = listOf(SipMethod.INVITE, SipMethod.BYE)
        )
        val allow = resp.firstHeader("Allow")
        assertNotNull(allow, "OPTIONS 200 OK 必须含 Allow 头")
        assertEquals("INVITE, BYE", allow)
    }

    @Test fun a1_t4_allowOrderFollowsInput() {
        val resp = SipBuilders.buildOptionsResponse(
            request = fakeOptions(),
            allowedMethods = listOf(SipMethod.BYE, SipMethod.ACK)
        )
        assertEquals("BYE, ACK", resp.firstHeader("Allow"))
    }

    @Test fun a1_t5_dateHeaderPresent() {
        val resp = SipBuilders.buildOptionsResponse(
            request = fakeOptions(),
            allowedMethods = listOf(SipMethod.OPTIONS)
        )
        val date = resp.firstHeader(SipHeader.DATE)
        assertNotNull(date, "OPTIONS 响应应带 Date 头(rfc1123 GMT)")
        // rfc1123 末尾固定 ' GMT'
        assertTrue(date.endsWith("GMT"), "Date 头应为 RFC1123 GMT 格式: $date")
    }

    @Test fun a1_t6_userAgentInjected() {
        val resp = SipBuilders.buildOptionsResponse(
            request = fakeOptions(),
            allowedMethods = listOf(SipMethod.OPTIONS),
            userAgent = "UvpSim/0.1"
        )
        assertEquals("UvpSim/0.1", resp.firstHeader(SipHeader.USER_AGENT))
    }

    @Test fun a1_t7_emptyBody() {
        val resp = SipBuilders.buildOptionsResponse(
            request = fakeOptions(),
            allowedMethods = listOf(SipMethod.INVITE)
        )
        assertEquals(0, resp.body.size)
    }
}

package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class SipParserTest {

    // ========== Request parsing ==========

    @Test fun parsesRegisterRequest() {
        val msg = SipParser.parse(SipSamples.registerRequest.encodeToByteArray())
        assertTrue(msg is SipRequest)
        assertEquals(SipMethod.REGISTER, msg.method)
        assertEquals("sip:34020000002000000001@3402000000", msg.requestUri)
        assertEquals("SIP/2.0", msg.sipVersion)
        assertEquals("8a9e2f5c@192.168.10.50", msg.callId())
        assertEquals("1 REGISTER", msg.cseqRaw())
    }

    @Test fun parsesInviteWithSdpBody() {
        val msg = SipParser.parse(SipSamples.inviteRealplay.encodeToByteArray())
        assertTrue(msg is SipRequest)
        assertEquals(SipMethod.INVITE, msg.method)
        val body = msg.body.decodeToString()
        assertTrue(body.contains("v=0"))
        assertTrue(body.contains("m=video 6000 RTP/AVP 96"))
        assertTrue(body.contains("a=rtpmap:96 PS/90000"))
        assertEquals("application/sdp", msg.firstHeader(SipHeader.CONTENT_TYPE))
    }

    @Test fun parsesMessageWithXmlBody() {
        val msg = SipParser.parse(SipSamples.keepaliveMessage.encodeToByteArray())
        assertTrue(msg is SipRequest)
        assertEquals(SipMethod.MESSAGE, msg.method)
        val body = msg.body.decodeToString()
        assertTrue(body.contains("<CmdType>Keepalive</CmdType>"))
        assertEquals("application/MANSCDP+xml", msg.firstHeader(SipHeader.CONTENT_TYPE))
    }

    @Test fun parsesBye() {
        val msg = SipParser.parse(SipSamples.bye.encodeToByteArray())
        assertTrue(msg is SipRequest)
        assertEquals(SipMethod.BYE, msg.method)
        assertEquals(0, msg.body.size)
    }

    // ========== Response parsing ==========

    @Test fun parses401Unauthorized() {
        val msg = SipParser.parse(SipSamples.register401.encodeToByteArray())
        assertTrue(msg is SipResponse)
        assertEquals(401, msg.statusCode)
        assertEquals("Unauthorized", msg.reasonPhrase)
        val www = msg.firstHeader(SipHeader.WWW_AUTHENTICATE)
        assertNotNull(www)
        assertTrue(www.contains("realm=\"3402000000\""))
        assertTrue(www.contains("nonce="))
    }

    @Test fun parses200Ok() {
        val msg = SipParser.parse(SipSamples.register200.encodeToByteArray())
        assertTrue(msg is SipResponse)
        assertEquals(200, msg.statusCode)
        assertEquals("OK", msg.reasonPhrase)
    }

    // ========== Round-trip(parse → toBytes → parse 等价) ==========

    @Test fun roundTripRegister() = roundTrip(SipSamples.registerRequest)
    @Test fun roundTrip401() = roundTrip(SipSamples.register401)
    @Test fun roundTrip200() = roundTrip(SipSamples.register200)
    @Test fun roundTripKeepalive() = roundTrip(SipSamples.keepaliveMessage)
    @Test fun roundTripInvite() = roundTrip(SipSamples.inviteRealplay)
    @Test fun roundTripBye() = roundTrip(SipSamples.bye)

    private fun roundTrip(sample: String) {
        val raw = sample.encodeToByteArray()
        val parsed = SipParser.parse(raw)
        val serialized = parsed.toBytes()
        val reparsed = SipParser.parse(serialized)
        // 不能字节级相等(我们规范化了 header name 大小写),但语义必须等价
        assertEquals(parsed::class, reparsed::class)
        when (parsed) {
            is SipRequest -> {
                reparsed as SipRequest
                assertEquals(parsed.method, reparsed.method)
                assertEquals(parsed.requestUri, reparsed.requestUri)
            }
            is SipResponse -> {
                reparsed as SipResponse
                assertEquals(parsed.statusCode, reparsed.statusCode)
                assertEquals(parsed.reasonPhrase, reparsed.reasonPhrase)
            }
        }
        assertEquals(parsed.callId(), reparsed.callId())
        assertTrue(parsed.body.contentEquals(reparsed.body), "Body must round-trip exactly")
    }

    // ========== 边界 / 错误情况 ==========

    @Test fun rejectsEmptyMessage() {
        assertFailsWith<SipParseException> { SipParser.parse(ByteArray(0)) }
    }

    @Test fun rejectsMalformedStartLine() {
        val raw = "GARBAGE\r\n\r\n".encodeToByteArray()
        assertFailsWith<SipParseException> { SipParser.parse(raw) }
    }

    @Test fun rejectsBadStatusCode() {
        val raw = "SIP/2.0 999X Reason\r\n\r\n".encodeToByteArray()
        assertFailsWith<SipParseException> { SipParser.parse(raw) }
    }

    @Test fun rejectsUnsupportedSipVersion() {
        val raw = "REGISTER sip:x@y SIP/3.0\r\n\r\n".encodeToByteArray()
        assertFailsWith<SipParseException> { SipParser.parse(raw) }
    }

    @Test fun rejectsHeaderWithoutColon() {
        val raw = "REGISTER sip:x@y SIP/2.0\r\nNoColonHere\r\n\r\n".encodeToByteArray()
        assertFailsWith<SipParseException> { SipParser.parse(raw) }
    }

    @Test fun acceptsLfOnlyLineEndings() {
        // 部分服务器违规用 LF,我们容忍
        val msg = SipParser.parse(SipSamples.lfOnlyMessage.encodeToByteArray())
        assertTrue(msg is SipRequest)
        assertEquals(SipMethod.REGISTER, msg.method)
        assertEquals("lf@10.0.0.1", msg.callId())
    }

    @Test fun handlesFoldedHeaderContinuation() {
        // RFC 3261 § 7.3.1: leading WSP folds
        val msg = SipParser.parse(SipSamples.foldedHeaderMessage.encodeToByteArray())
        assertTrue(msg is SipRequest)
        val subject = msg.firstHeader("Subject")
        assertNotNull(subject)
        assertTrue(subject.contains("continuation of the subject"))
        assertTrue(subject.contains("and another fold"))
    }

    @Test fun handlesCompactHeaderForms() {
        // v=Via, f=From, t=To, i=Call-ID, l=Content-Length
        val msg = SipParser.parse(SipSamples.compactHeaderMessage.encodeToByteArray())
        assertTrue(msg is SipRequest)
        assertNotNull(msg.via())
        assertNotNull(msg.fromHeader())
        assertNotNull(msg.toHeader())
        assertEquals("c@10.0.0.1", msg.callId())
    }

    // ========== Header API ==========

    @Test fun firstHeaderIsCaseInsensitive() {
        val msg = SipParser.parse(SipSamples.registerRequest.encodeToByteArray())
        assertEquals(msg.firstHeader("call-id"), msg.firstHeader("Call-ID"))
        assertEquals(msg.firstHeader("CALL-ID"), msg.firstHeader("Call-ID"))
    }

    @Test fun multipleViaCanBeListed() {
        val twoVia = listOf(
            "REGISTER sip:x@y SIP/2.0",
            "Via: SIP/2.0/UDP host1:5060;branch=b1",
            "Via: SIP/2.0/UDP host2:5060;branch=b2",
            "From: <sip:u@e>;tag=t",
            "To: <sip:u@e>",
            "Call-ID: c",
            "CSeq: 1 REGISTER",
            "Content-Length: 0",
            "",
            ""
        ).joinToString("\r\n")
        val msg = SipParser.parse(twoVia.encodeToByteArray())
        val vias = msg.allHeaders(SipHeader.VIA)
        assertEquals(2, vias.size)
        assertTrue(vias[0].contains("host1"))
        assertTrue(vias[1].contains("host2"))
    }

    // ========== Content-Length 行为 ==========

    @Test fun bodyTruncatedByContentLength() {
        // body 实际比 Content-Length 长 — 应该按 Content-Length 截断
        val xmlBody = "<short/>"
        val msgText = listOf(
            "MESSAGE sip:x@y SIP/2.0",
            "Via: SIP/2.0/UDP h:5060",
            "From: <sip:f@e>;tag=t",
            "To: <sip:t@e>",
            "Call-ID: c",
            "CSeq: 1 MESSAGE",
            "Content-Type: application/MANSCDP+xml",
            "Content-Length: 8",
            "",
            "$xmlBody-extra-data-after"
        ).joinToString("\r\n")
        val msg = SipParser.parse(msgText.encodeToByteArray())
        assertEquals(8, msg.body.size)
        assertEquals(xmlBody, msg.body.decodeToString())
    }

    @Test fun rejectsContentLengthExceedingBody() {
        val text = listOf(
            "MESSAGE sip:x@y SIP/2.0",
            "Via: SIP/2.0/UDP h:5060",
            "From: <sip:f@e>;tag=t",
            "To: <sip:t@e>",
            "Call-ID: c",
            "CSeq: 1 MESSAGE",
            "Content-Length: 100",
            "",
            "short"
        ).joinToString("\r\n")
        assertFailsWith<SipParseException> { SipParser.parse(text.encodeToByteArray()) }
    }
}

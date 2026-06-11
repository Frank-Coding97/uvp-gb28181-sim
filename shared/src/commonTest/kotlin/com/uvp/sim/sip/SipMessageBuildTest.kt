package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SipMessageBuildTest {

    /** 手写构造一个 REGISTER → 序列化 → 跟样例字段语义一致 */
    @Test fun buildRegister() {
        val req = SipRequest(
            method = SipMethod.REGISTER,
            requestUri = "sip:34020000002000000001@3402000000",
            headers = listOf(
                SipMessage.Header("Via", "SIP/2.0/UDP 192.168.10.50:5060;rport;branch=z9hG4bK1234567890"),
                SipMessage.Header("From", "<sip:34020000001110000001@3402000000>;tag=abc123"),
                SipMessage.Header("To", "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header("Call-ID", "8a9e2f5c@192.168.10.50"),
                SipMessage.Header("CSeq", "1 REGISTER"),
                SipMessage.Header("Contact", "<sip:34020000001110000001@192.168.10.50:5060>"),
                SipMessage.Header("Max-Forwards", "70"),
                SipMessage.Header("User-Agent", "UVP-Sim/0.1"),
                SipMessage.Header("Expires", "3600"),
            )
        )
        val bytes = req.toBytes()
        val text = bytes.decodeToString()
        // 起始行
        assertTrue(text.startsWith("REGISTER sip:34020000002000000001@3402000000 SIP/2.0\r\n"))
        // header 全在
        assertTrue(text.contains("Call-ID: 8a9e2f5c@192.168.10.50\r\n"))
        assertTrue(text.contains("CSeq: 1 REGISTER\r\n"))
        // Content-Length 自动加(原本未设)
        assertTrue(text.contains("Content-Length: 0\r\n"))
        // 起止 boundary
        assertTrue(text.endsWith("\r\n\r\n"))

        // 重新解析回来等价
        val parsed = SipParser.parse(bytes) as SipRequest
        assertEquals(SipMethod.REGISTER, parsed.method)
        assertEquals("8a9e2f5c@192.168.10.50", parsed.callId())
    }

    @Test fun buildResponseAutoSetsContentLength() {
        val resp = SipResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = listOf(
                SipMessage.Header("Via", "SIP/2.0/UDP h:5060"),
                SipMessage.Header("From", "<sip:f@e>;tag=t"),
                SipMessage.Header("To", "<sip:t@e>;tag=server"),
                SipMessage.Header("Call-ID", "c"),
                SipMessage.Header("CSeq", "1 REGISTER"),
                // 故意不设 Content-Length
            ),
            body = "hello".encodeToByteArray()
        )
        val text = resp.toBytes().decodeToString()
        assertTrue(text.contains("Content-Length: 5\r\n"))
        assertTrue(text.endsWith("\r\n\r\nhello"))
    }

    @Test fun buildOverridesStaleContentLength() {
        // 用户传了过期 Content-Length=999,但 body 实际只 5 字节 — 必须按 5 序列化
        val resp = SipResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = listOf(
                SipMessage.Header("Via", "SIP/2.0/UDP h:5060"),
                SipMessage.Header("Call-ID", "c"),
                SipMessage.Header("Content-Length", "999"),
            ),
            body = "hello".encodeToByteArray()
        )
        val text = resp.toBytes().decodeToString()
        assertTrue(text.contains("Content-Length: 5\r\n"))
        assertTrue(!text.contains("Content-Length: 999"))
    }
}

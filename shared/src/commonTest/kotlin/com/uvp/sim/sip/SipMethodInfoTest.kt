package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T01: SipMethod 加 INFO 枚举值测试。
 *
 * 完整 INFO + MANSRTSP body 解析在 [MansRtspParserTest](T02),
 * 此处只覆盖 SipMethod 枚举本身 + parser 识别 method。
 */
class SipMethodInfoTest {

    @Test fun fromStringInfoUppercase() {
        assertEquals(SipMethod.INFO, SipMethod.fromString("INFO"))
    }

    @Test fun fromStringInfoLowercase() {
        assertEquals(SipMethod.INFO, SipMethod.fromString("info"))
    }

    @Test fun fromStringInfoMixedCase() {
        assertEquals(SipMethod.INFO, SipMethod.fromString("Info"))
    }

    @Test fun fromStringInvalidVariant() {
        assertNull(SipMethod.fromString("INFOX"))
    }

    @Test fun parsesInfoRequestLine() {
        // RTSP-style INFO request via SIP — INFO body 解析放 T02。
        val raw = (
            "INFO sip:34020000001320000001@192.168.1.10:5060 SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP 192.168.1.99:5060;branch=z9hG4bK-info-1\r\n" +
                "From: <sip:34020000002000000001@3402000000>;tag=ft1\r\n" +
                "To: <sip:34020000001320000001@3402000000>;tag=tt1\r\n" +
                "Call-ID: info-call-1@192.168.1.99\r\n" +
                "CSeq: 5 INFO\r\n" +
                "Content-Type: Application/MANSRTSP\r\n" +
                "Content-Length: 0\r\n\r\n"
            )
        val msg = SipParser.parse(raw.encodeToByteArray())
        assertTrue(msg is SipRequest)
        assertEquals(SipMethod.INFO, msg.method)
        assertEquals("info-call-1@192.168.1.99", msg.callId())
        assertEquals("5 INFO", msg.cseqRaw())
    }
}

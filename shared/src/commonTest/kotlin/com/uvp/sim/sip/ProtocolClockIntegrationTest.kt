package com.uvp.sim.sip

import com.uvp.sim.domain.ProtocolClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ProtocolClockIntegrationTest {
    @Test
    fun `SIP Date header uses calibrated protocol clock`() {
        ProtocolClock.install { Instant.parse("2026-08-21T08:16:11Z") }
        try {
            val request = SipRequest(
                method = SipMethod.MESSAGE,
                requestUri = "sip:platform@example.test",
                headers = listOf(
                    SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-test"),
                    SipMessage.Header(SipHeader.FROM, "<sip:device@example.test>;tag=a"),
                    SipMessage.Header(SipHeader.TO, "<sip:platform@example.test>"),
                    SipMessage.Header(SipHeader.CALL_ID, "clock-test"),
                    SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                ),
            )

            val response = SipBuilders.buildSimpleError(request, 400, "Bad Request")

            assertEquals("Fri, 21 Aug 2026 08:16:11 GMT", response.firstHeader(SipHeader.DATE))
        } finally {
            ProtocolClock.reset()
        }
    }
}

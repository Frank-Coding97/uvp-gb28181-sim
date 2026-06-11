package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

class SdpParserTest {

    /** Real-world WVP-issued INVITE Play offer. */
    private val wvpOffer = """
        v=0
        o=34020000002000000001 0 0 IN IP4 192.168.10.222
        s=Play
        c=IN IP4 192.168.10.222
        t=0 0
        m=video 30000 RTP/AVP 96
        a=recvonly
        a=rtpmap:96 PS/90000
        y=0100000001
    """.trimIndent().replace("\n", "\r\n")

    /** GB28181-2016 minimal offer (no y= line). */
    private val minimalOffer = """
        v=0
        o=34020000002000000001 0 0 IN IP4 192.168.10.222
        s=Play
        c=IN IP4 192.168.10.222
        t=0 0
        m=video 30000 RTP/AVP 96
        a=recvonly
        a=rtpmap:96 PS/90000
    """.trimIndent().replace("\n", "\r\n")

    @Test fun parseWvpStandardOffer() {
        val offer = SdpParser.parseOffer(wvpOffer)
        assertEquals("192.168.10.222", offer.remoteIp)
        assertEquals(30000, offer.remotePort)
        assertEquals("0100000001", offer.ssrc)
        assertEquals(SdpDirection.RECVONLY, offer.direction)
    }

    @Test fun parseMinimalOfferYieldsNullSsrc() {
        val offer = SdpParser.parseOffer(minimalOffer)
        assertNull(offer.ssrc)
        assertEquals(30000, offer.remotePort)
    }

    @Test fun parseFromBytes() {
        val offer = SdpParser.parseOffer(wvpOffer.encodeToByteArray())
        assertEquals("192.168.10.222", offer.remoteIp)
        assertEquals("0100000001", offer.ssrc)
    }

    @Test fun rejectsOfferWithoutConnectLine() {
        val bad = "v=0\r\nm=video 1234 RTP/AVP 96\r\n"
        assertFailsWith<IllegalArgumentException> { SdpParser.parseOffer(bad) }
    }

    @Test fun rejectsOfferWithoutVideoLine() {
        val bad = "v=0\r\nc=IN IP4 1.2.3.4\r\n"
        assertFailsWith<IllegalArgumentException> { SdpParser.parseOffer(bad) }
    }

    @Test fun toleratesLfOnlyLineEndings() {
        val lfOnly = wvpOffer.replace("\r\n", "\n")
        val offer = SdpParser.parseOffer(lfOnly)
        assertEquals(30000, offer.remotePort)
    }
}

class SdpAnswerTest {

    @Test fun buildPlayAnswerProducesAllRequiredLines() {
        val answer = SdpAnswer.buildPlayAnswer(
            deviceId = "35020000001310000001",
            localIp = "192.168.10.112",
            localRtpPort = 30022,
            ssrc = "0100000001",
            sessionName = "Play"
        )
        // Required lines per GB28181 § 8
        assertTrue(answer.contains("v=0\r\n"))
        assertTrue(answer.contains("o=35020000001310000001 0 0 IN IP4 192.168.10.112\r\n"))
        assertTrue(answer.contains("s=Play\r\n"))
        assertTrue(answer.contains("c=IN IP4 192.168.10.112\r\n"))
        assertTrue(answer.contains("t=0 0\r\n"))
        assertTrue(answer.contains("m=video 30022 RTP/AVP 96\r\n"))
        assertTrue(answer.contains("a=sendonly\r\n"))   // we send
        assertTrue(answer.contains("a=rtpmap:96 PS/90000\r\n"))
        assertTrue(answer.contains("y=0100000001\r\n"))
    }

    @Test fun answerPreservesOfferSsrc() {
        val offer = SdpParser.parseOffer(
            """
            v=0
            o=server 0 0 IN IP4 1.2.3.4
            s=Play
            c=IN IP4 1.2.3.4
            t=0 0
            m=video 30000 RTP/AVP 96
            a=recvonly
            a=rtpmap:96 PS/90000
            y=0987654321
            """.trimIndent().replace("\n", "\r\n")
        )
        val answer = SdpAnswer.buildPlayAnswer(
            deviceId = "device", localIp = "192.168.0.1",
            localRtpPort = 30000, ssrc = offer.ssrc!!
        )
        assertTrue(answer.contains("y=0987654321\r\n"))
    }

    @Test fun rejectsInvalidSsrc() {
        assertFailsWith<IllegalArgumentException> {
            SdpAnswer.buildPlayAnswer("d", "1.1.1.1", 30000, "abc")
        }
        assertFailsWith<IllegalArgumentException> {
            SdpAnswer.buildPlayAnswer("d", "1.1.1.1", 30000, "12345")  // too short
        }
    }
}

class SsrcUtilsTest {

    @Test fun toRtpIntForLargeSsrc() {
        // 4000000000 fits in unsigned 32-bit, signed Int wraps
        val rtpSigned = SsrcUtils.toRtpInt("4000000000")
        // Verify round-trip via Long
        val back = (rtpSigned.toLong() and 0xFFFFFFFFL)
        assertEquals(4_000_000_000L, back)
    }

    @Test fun toRtpIntForSmallSsrc() {
        assertEquals(100000001, SsrcUtils.toRtpInt("0100000001"))
    }

    @Test fun toRtpIntRejectsBadLength() {
        assertFailsWith<IllegalArgumentException> { SsrcUtils.toRtpInt("123") }
    }

    @Test fun generateProducesValidLength() {
        val ssrc = SsrcUtils.generate(realtime = true, domainCode = "35020", sequence = 42)
        assertEquals(10, ssrc.length)
        assertTrue(ssrc.startsWith("0"))           // realtime prefix
        assertTrue(ssrc.contains("35020"))         // domain code
    }

    @Test fun generateRejectsBadDomainCode() {
        assertFailsWith<IllegalArgumentException> {
            SsrcUtils.generate(realtime = true, domainCode = "ABC", sequence = 1)
        }
    }
}

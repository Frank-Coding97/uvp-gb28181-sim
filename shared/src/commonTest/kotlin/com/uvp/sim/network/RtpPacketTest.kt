package com.uvp.sim.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T4 — RTP 固定 12 字节头解析(RFC 3550)。 */
class RtpPacketTest {

    @Test
    fun parsesValidPacket() {
        // 80 08 00 01  00 00 00 A0  12 34 56 78 | payload "hello"
        val bytes = byteArrayOf(
            0x80.toByte(), 0x08, 0x00, 0x01,
            0x00, 0x00, 0x00, 0xA0.toByte(),
            0x12, 0x34, 0x56, 0x78
        ) + "hello".encodeToByteArray()
        val rtp = RtpPacket.parse(bytes)
        assertNotNull(rtp)
        assertEquals(2, rtp.version)
        assertEquals(8, rtp.payloadType)
        assertEquals(1, rtp.sequence)
        assertEquals(160L, rtp.timestamp)
        assertEquals(0x12345678L, rtp.ssrc)
        assertEquals("hello", rtp.payload.decodeToString())
    }

    @Test
    fun tooShortReturnsNull() {
        assertNull(RtpPacket.parse(ByteArray(11)))
    }

    @Test
    fun wrongVersionReturnsNull() {
        val bytes = byteArrayOf(0x40, 0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)  // version=1
        assertNull(RtpPacket.parse(bytes))
    }

    @Test
    fun extractsMarkerAndPadding() {
        // padding=1, marker=1, PT=8
        val bytes = byteArrayOf(0xA0.toByte(), 0x88.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val rtp = RtpPacket.parse(bytes)
        assertNotNull(rtp)
        assertTrue(rtp.padding)
        assertTrue(rtp.marker)
        assertEquals(8, rtp.payloadType)
    }

    @Test
    fun parsesCsrcCount() {
        val bytes = byteArrayOf(0x82.toByte(), 0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)  // CC=2
        val rtp = RtpPacket.parse(bytes)
        assertNotNull(rtp)
        assertEquals(2, rtp.csrcCount)
    }
}

package com.uvp.sim.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class NtpPacketTest {
    @Test
    fun `request is client mode and carries transmit timestamp`() {
        val now = Instant.parse("2026-08-21T08:16:11.314Z")

        val packet = NtpPacket.request(now)

        assertEquals(48, packet.size)
        assertEquals(0x23, packet[0].toInt() and 0xff)
        assertEquals(now.toEpochMilliseconds(), NtpPacket.readTimestamp(packet, 40).toEpochMilliseconds())
    }

    @Test
    fun `response applies standard four timestamp offset`() {
        val t1 = Instant.parse("2026-08-21T08:16:11.000Z")
        val t2 = Instant.parse("2026-08-21T08:16:11.120Z")
        val t3 = Instant.parse("2026-08-21T08:16:11.130Z")
        val t4 = Instant.parse("2026-08-21T08:16:11.050Z")
        val response = ByteArray(48).also {
            it[0] = 0x24
            it[1] = 2
            NtpPacket.writeTimestamp(it, 24, t1)
            NtpPacket.writeTimestamp(it, 32, t2)
            NtpPacket.writeTimestamp(it, 40, t3)
        }

        val sample = NtpPacket.parseResponse(response, t1, t4)

        assertEquals(100L, sample.offsetMillis)
        assertEquals(t4.toEpochMilliseconds() + 100L, sample.instant.toEpochMilliseconds())
    }

    @Test
    fun `unsynchronised server response is rejected`() {
        val response = ByteArray(48).also {
            it[0] = 0xE4.toByte()
            it[1] = 2
        }

        assertFailsWith<IllegalArgumentException> {
            NtpPacket.parseResponse(response, Instant.DISTANT_PAST, Instant.DISTANT_PAST)
        }
    }
}

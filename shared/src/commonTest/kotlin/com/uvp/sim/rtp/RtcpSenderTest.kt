package com.uvp.sim.rtp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC3550 § 6.4.1 Sender Report 28-byte 包验证。
 *
 * 8 用例覆盖:
 *  1. 包长度 28 字节
 *  2. V=2 / P=0 / RC=0(byte 0)
 *  3. PT=200 SR(byte 1)
 *  4. length=6(byte 2-3 big-endian)
 *  5. SSRC big-endian(byte 4-7)
 *  6. NTP 高 32 = 1900 偏移(byte 8-11)
 *  7. NTP 低 32 ≈ 0x80000000 当 frac=500ms(byte 12-15)
 *  8. unsigned 32 截断:packet count > 2^31
 */
class RtcpSenderTest {

    @Test fun `SR packet is 28 bytes`() {
        val sr = RtcpSender.buildSR(0x12345678, 0L, 0L, 0L, 0L)
        assertEquals(28, sr.size)
    }

    @Test fun `byte0 V=2 P=0 RC=0`() {
        val sr = RtcpSender.buildSR(0, 0L, 0L, 0L, 0L)
        // 0x80 = 1000 0000 -> V=10b=2, P=0, RC=00000=0
        assertEquals(0x80.toByte(), sr[0])
    }

    @Test fun `byte1 PT=200`() {
        val sr = RtcpSender.buildSR(0, 0L, 0L, 0L, 0L)
        assertEquals(200, sr[1].toInt() and 0xFF)
    }

    @Test fun `length=6 big-endian`() {
        val sr = RtcpSender.buildSR(0, 0L, 0L, 0L, 0L)
        // length 字段:RTCP 包长 / 4 - 1 = 28/4 - 1 = 6
        assertEquals(0, sr[2].toInt() and 0xFF)
        assertEquals(6, sr[3].toInt() and 0xFF)
    }

    @Test fun `SSRC big-endian`() {
        val sr = RtcpSender.buildSR(0x12345678, 0L, 0L, 0L, 0L)
        assertEquals(0x12, sr[4].toInt() and 0xFF)
        assertEquals(0x34, sr[5].toInt() and 0xFF)
        assertEquals(0x56, sr[6].toInt() and 0xFF)
        assertEquals(0x78, sr[7].toInt() and 0xFF)
    }

    @Test fun `NTP high32 epoch=0 maps to 1970-1900 offset 2208988800`() {
        val sr = RtcpSender.buildSR(0, 0L, 0L, 0L, 0L)
        val high = (
            ((sr[8].toLong() and 0xFF) shl 24) or
            ((sr[9].toLong() and 0xFF) shl 16) or
            ((sr[10].toLong() and 0xFF) shl 8) or
            (sr[11].toLong() and 0xFF)
        )
        assertEquals(2208988800L, high)
    }

    @Test fun `NTP low32 frac 500ms ≈ 0x80000000`() {
        val sr = RtcpSender.buildSR(0, 500L, 0L, 0L, 0L)
        val low = (
            ((sr[12].toLong() and 0xFF) shl 24) or
            ((sr[13].toLong() and 0xFF) shl 16) or
            ((sr[14].toLong() and 0xFF) shl 8) or
            (sr[15].toLong() and 0xFF)
        )
        // 500/1000 = 0.5 → 高位 1 半精度 0x80000000(允许小幅误差)
        val expected = 0x80000000L
        val diff = if (low > expected) low - expected else expected - low
        assertTrue(diff < 0x100000L, "low=$low expected≈$expected diff=$diff")
    }

    @Test fun `packet count larger than Int max truncates to unsigned 32 bits`() {
        // 3_000_000_000 > Int.MAX (2_147_483_647) → 应作为 unsigned 32 写入
        val sr = RtcpSender.buildSR(0, 0L, 0L, 3_000_000_000L, 0L)
        val pc = (
            ((sr[20].toLong() and 0xFF) shl 24) or
            ((sr[21].toLong() and 0xFF) shl 16) or
            ((sr[22].toLong() and 0xFF) shl 8) or
            (sr[23].toLong() and 0xFF)
        )
        assertEquals(3_000_000_000L, pc)
    }
}

private fun assertTrue(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

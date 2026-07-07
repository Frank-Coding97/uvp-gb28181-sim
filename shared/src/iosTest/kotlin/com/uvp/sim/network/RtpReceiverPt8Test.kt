package com.uvp.sim.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * T-E3-1:G.711A(payload type = 8)RTP 解析 verify。
 *
 * 关注点:
 *   - pt=8 从 12-byte fixed header 的第 2 字节低 7 bit 提取正确
 *   - marker bit / SSRC / timestamp / sequence 与国标 §F.2.1 附录一致
 *   - CSRC 存在时 header offset 正确;payload 160 bytes 对齐 20ms @ 8kHz
 */
class RtpReceiverPt8Test {

    @Test
    fun parses_pt_8_from_second_header_byte() {
        // v=2, no padding, no ext, csrc=0, marker=0, pt=8
        // seq=100, ts=0x12345678, ssrc=0xDEADBEEF
        val header = byteArrayOf(
            0x80.toByte(), 0x08, // pt=8
            0x00, 0x64,          // seq 100
            0x12, 0x34, 0x56, 0x78,
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )
        val payload = ByteArray(160) { (it % 128).toByte() } // 160 bytes alaw
        val rtp = RtpPacket.parse(header + payload)
        checkNotNull(rtp)
        assertEquals(8, rtp.payloadType)
        assertEquals(100, rtp.sequence)
        assertEquals(0x12345678L, rtp.timestamp)
        assertEquals(0xDEADBEEFL, rtp.ssrc)
        assertFalse(rtp.marker)
        assertContentEquals(payload, rtp.payload)
        assertEquals(160, rtp.payload.size)
    }

    @Test
    fun marker_bit_extracted_correctly() {
        // marker=1, pt=8
        val header = byteArrayOf(
            0x80.toByte(), 0x88.toByte(),
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val rtp = RtpPacket.parse(header + ByteArray(160))
        checkNotNull(rtp)
        assertEquals(8, rtp.payloadType)
        kotlin.test.assertTrue(rtp.marker, "marker bit should be set")
    }

    @Test
    fun csrc_shifts_header_offset() {
        // v=2, cc=2, pt=8 → header = 12 + 2*4 = 20 bytes
        val fixed = byteArrayOf(
            0x82.toByte(), 0x08, // v=2, cc=2, pt=8
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val csrc = ByteArray(8) // 2 CSRC × 4 bytes
        val payload = ByteArray(160) { it.toByte() }
        val rtp = RtpPacket.parse(fixed + csrc + payload)
        checkNotNull(rtp)
        assertEquals(2, rtp.csrcCount)
        assertContentEquals(payload, rtp.payload)
    }
}

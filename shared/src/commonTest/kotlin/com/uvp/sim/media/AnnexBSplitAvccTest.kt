package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for AVCC → NAL split logic added for iOS VideoToolbox output.
 * See task T7 in wiki/projects/uvp-gb28181-sim/tasks/ios-media-v1.1.md
 */
class AnnexBSplitAvccTest {

    @Test
    fun single_nal_idr() {
        val avcc = byteArrayOf(0, 0, 0, 5, 0x65.toByte(), 1, 2, 3, 4)
        val nals = AnnexB.splitAvcc(avcc)
        assertEquals(1, nals.size)
        assertEquals(5, nals[0].size)
        assertEquals(NalType.IDR, nals[0][0].toInt() and 0x1F)
        assertContentEquals(byteArrayOf(0x65.toByte(), 1, 2, 3, 4), nals[0])
    }

    @Test
    fun multiple_nals_sps_pps() {
        val avcc = byteArrayOf(
            0, 0, 0, 3, 0x67.toByte(), 0xAA.toByte(), 0xBB.toByte(),
            0, 0, 0, 2, 0x68.toByte(), 0xCC.toByte()
        )
        val nals = AnnexB.splitAvcc(avcc)
        assertEquals(2, nals.size)
        assertEquals(NalType.SPS, nals[0][0].toInt() and 0x1F)
        assertEquals(NalType.PPS, nals[1][0].toInt() and 0x1F)
        assertEquals(3, nals[0].size)
        assertEquals(2, nals[1].size)
    }

    @Test
    fun empty_buffer_returns_empty_list() {
        assertTrue(AnnexB.splitAvcc(byteArrayOf()).isEmpty())
    }

    @Test
    fun buffer_smaller_than_length_prefix_returns_empty() {
        // 2 bytes, prefix is 4 bytes → nothing parseable
        assertTrue(AnnexB.splitAvcc(byteArrayOf(0, 5)).isEmpty())
    }

    @Test
    fun truncated_nal_body_bails_gracefully() {
        // Length says 10 but only 3 bytes of body → bail, no exception
        val avcc = byteArrayOf(0, 0, 0, 10, 1, 2, 3)
        assertTrue(AnnexB.splitAvcc(avcc).isEmpty())
    }

    @Test
    fun partial_parse_when_second_nal_truncated() {
        val avcc = byteArrayOf(
            0, 0, 0, 3, 0x67.toByte(), 0xAA.toByte(), 0xBB.toByte(),  // valid NAL
            0, 0, 0, 100, 1, 2                                          // truncated NAL
        )
        val nals = AnnexB.splitAvcc(avcc)
        assertEquals(1, nals.size, "should return first valid NAL")
        assertEquals(NalType.SPS, nals[0][0].toInt() and 0x1F)
    }

    @Test
    fun zero_length_field_bails() {
        // Length=0 should not produce an infinite loop
        val avcc = byteArrayOf(0, 0, 0, 0, 1, 2, 3)
        assertTrue(AnnexB.splitAvcc(avcc).isEmpty())
    }

    @Test
    fun length_prefix_2_bytes_supported() {
        // GB28181 rare case but algorithmically supported
        val avcc = byteArrayOf(0, 3, 0x67.toByte(), 0xAA.toByte(), 0xBB.toByte())
        val nals = AnnexB.splitAvcc(avcc, lengthPrefixSize = 2)
        assertEquals(1, nals.size)
        assertEquals(3, nals[0].size)
    }

    @Test
    fun length_prefix_out_of_range_throws() {
        try {
            AnnexB.splitAvcc(byteArrayOf(1, 2), lengthPrefixSize = 5)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1..4"))
        }
    }
}

private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
    assertEquals(expected.size, actual.size, "size mismatch")
    for (i in expected.indices) {
        assertEquals(expected[i], actual[i], "byte $i mismatch")
    }
}

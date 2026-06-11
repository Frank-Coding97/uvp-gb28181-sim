package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnexBTest {

    @Test fun emptyInputReturnsEmpty() {
        assertEquals(0, AnnexB.splitNals(ByteArray(0)).size)
    }

    @Test fun singleNalWith4ByteStartCode() {
        val nal = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x01) + nal
        val result = AnnexB.splitNals(data)
        assertEquals(1, result.size)
        assertTrue(nal.contentEquals(result[0]))
    }

    @Test fun singleNalWith3ByteStartCode() {
        val nal = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())
        val data = byteArrayOf(0x00, 0x00, 0x01) + nal
        val result = AnnexB.splitNals(data)
        assertEquals(1, result.size)
        assertTrue(nal.contentEquals(result[0]))
    }

    @Test fun multipleNalsMixedStartCodes() {
        val sps = byteArrayOf(0x67, 0x42)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x01) + sps +
            byteArrayOf(0x00, 0x00, 0x01) + pps +
            byteArrayOf(0x00, 0x00, 0x00, 0x01) + idr
        val result = AnnexB.splitNals(data)
        assertEquals(3, result.size)
        assertTrue(sps.contentEquals(result[0]))
        assertTrue(pps.contentEquals(result[1]))
        assertTrue(idr.contentEquals(result[2]))
    }

    @Test fun ignoresLeadingNoise() {
        // No start code at the start; just garbage
        val data = byteArrayOf(0x99.toByte(), 0xFF.toByte(), 0xAA.toByte())
        assertEquals(0, AnnexB.splitNals(data).size)
    }

    @Test fun nalWithEmbeddedZerosKeepsBoundary() {
        // 0x00 0x00 in middle of NAL data is fine if not followed by 0x01
        val nal = byteArrayOf(0x65, 0x00, 0x00, 0x05, 0x88.toByte())
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x01) + nal
        val result = AnnexB.splitNals(data)
        assertEquals(1, result.size)
        assertTrue(nal.contentEquals(result[0]))
    }
}

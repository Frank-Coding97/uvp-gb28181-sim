package com.uvp.sim.recording

import com.uvp.sim.media.NalType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CMSampleBufferBuilderTest {

    @Test
    fun avcc_payload_prefixes_each_nal_with_big_endian_length() {
        val builder = CMSampleBufferBuilder()
        val sps = nal(NalType.SPS, 0x11, 0x22)
        val idr = nal(NalType.IDR, 0x33, 0x44, 0x55)

        val payload = builder.buildAvccPayload(listOf(sps, idr))

        assertBytes(
            byteArrayOf(
                0, 0, 0, 3, 0x67, 0x11, 0x22,
                0, 0, 0, 4, 0x65, 0x33, 0x44, 0x55,
            ),
            payload,
        )
    }

    @Test
    fun parameter_sets_are_cached_from_keyframe_nals() {
        val builder = CMSampleBufferBuilder()

        assertFalse(builder.hasFormatDescriptionInputs())
        builder.observeParameterSets(listOf(nal(NalType.SPS, 1), nal(NalType.PPS, 2)))

        assertTrue(builder.hasFormatDescriptionInputs())
    }

    @Test
    fun empty_nals_are_ignored_in_avcc_payload() {
        val builder = CMSampleBufferBuilder()

        val payload = builder.buildAvccPayload(listOf(byteArrayOf(), nal(NalType.IDR, 1)))

        assertBytes(byteArrayOf(0, 0, 0, 2, 0x65, 1), payload)
    }

    private fun nal(type: Int, vararg payload: Int): ByteArray =
        byteArrayOf(((0x60) or type).toByte()) + payload.map { it.toByte() }.toByteArray()

    private fun assertBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "size mismatch")
        expected.indices.forEach { i ->
            assertEquals(expected[i], actual[i], "byte $i mismatch")
        }
    }
}

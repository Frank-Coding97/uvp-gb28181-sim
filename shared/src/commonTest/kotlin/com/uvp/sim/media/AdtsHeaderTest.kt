package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-B2-3:ADTS header 字节精确性。
 *
 * 头 7 字节按 MPEG-4 AAC-LC + 44.1 kHz + mono + frame_length 分段验字段。
 */
class AdtsHeaderTest {

    @Test
    fun wrap_44100_mono_header_fields() {
        val raw = ByteArray(100) { it.toByte() }
        val out = AdtsHeader.wrap(raw, sampleRateHz = 44_100, channels = 1)
        assertEquals(107, out.size, "总长 = raw + 7 头字节")

        // syncword low + MPEG-4 + no CRC
        assertEquals(0xFF.toByte(), out[0])
        assertEquals(0xF0, out[1].toInt() and 0xF0, "0xF0 高 4 位 sync")
        assertEquals(0x00, out[1].toInt() and 0x08, "MPEG-4 ID bit")
        assertEquals(0x01, out[1].toInt() and 0x01, "protection_absent = 1 (no CRC)")

        // profile 2 bits = AAC LC(1),编码 = 1
        val profileCode = (out[2].toInt() ushr 6) and 0x03
        assertEquals(0x01, profileCode, "AAC LC profile = 1")

        // sampling_frequency_index = 4 for 44.1kHz
        val freqIdx = (out[2].toInt() ushr 2) and 0x0F
        assertEquals(4, freqIdx, "sampling_frequency_index 44.1kHz → 4")

        // channel_configuration = 1 for mono
        val chanCfg = ((out[2].toInt() and 0x01) shl 2) or ((out[3].toInt() ushr 6) and 0x03)
        assertEquals(1, chanCfg, "channel_configuration mono → 1")

        // frame_length = 107 (13 bits)
        val frameLen = ((out[3].toInt() and 0x03) shl 11) or
            ((out[4].toInt() and 0xFF) shl 3) or
            ((out[5].toInt() ushr 5) and 0x07)
        assertEquals(107, frameLen, "frame_length 字段 = out.size")

        // buffer_fullness = 0x7FF (VBR)
        val fullness = ((out[5].toInt() and 0x1F) shl 6) or ((out[6].toInt() ushr 2) and 0x3F)
        assertEquals(0x7FF, fullness)
    }

    @Test
    fun sampling_freq_index_table() {
        assertEquals(0, AdtsHeader.samplingFrequencyIndex(96_000))
        assertEquals(3, AdtsHeader.samplingFrequencyIndex(48_000))
        assertEquals(4, AdtsHeader.samplingFrequencyIndex(44_100))
        assertEquals(8, AdtsHeader.samplingFrequencyIndex(16_000))
        assertEquals(11, AdtsHeader.samplingFrequencyIndex(8_000))
        assertEquals(15, AdtsHeader.samplingFrequencyIndex(9999), "未知采样率返回 15(reserved)")
    }

    @Test
    fun wrap_16000_stereo_channel_bits() {
        val raw = ByteArray(50)
        val out = AdtsHeader.wrap(raw, sampleRateHz = 16_000, channels = 2)
        val chanCfg = ((out[2].toInt() and 0x01) shl 2) or ((out[3].toInt() ushr 6) and 0x03)
        assertEquals(2, chanCfg)
        val freqIdx = (out[2].toInt() ushr 2) and 0x0F
        assertEquals(8, freqIdx)
    }

    @Test
    fun wrap_preserves_payload_bytes() {
        val raw = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val out = AdtsHeader.wrap(raw, 44_100, 1)
        for (i in raw.indices) {
            assertEquals(raw[i], out[7 + i])
        }
        assertTrue(out.size == raw.size + 7)
    }
}

package com.uvp.sim.media

/**
 * MPEG-4 AAC-LC ADTS header helper。
 *
 * 与 [AndroidAudioStreamer.withAdts] 语义一致 —— iOS 侧 [IosAacEncoder] 也走这条路。
 *
 * ADTS header 布局(7 字节,不带 CRC):
 * ```
 *   syncword: 12 bits = 0xFFF
 *   ID: 1 bit = 0 (MPEG-4)
 *   layer: 2 bits = 0
 *   protection_absent: 1 bit = 1 (no CRC)
 *   profile: 2 bits = AAC LC (1)  → 减 1 编码 = 1
 *   sampling_frequency_index: 4 bits
 *   private_bit: 1 bit = 0
 *   channel_configuration: 3 bits
 *   original/copy: 1 bit = 0
 *   home: 1 bit = 0
 *   copyright_id_bit: 1 bit = 0
 *   copyright_id_start: 1 bit = 0
 *   frame_length: 13 bits = raw AAC + 7 (header)
 *   adts_buffer_fullness: 11 bits = 0x7FF (VBR)
 *   number_of_raw_data_blocks_in_frame: 2 bits = 0
 * ```
 */
object AdtsHeader {

    /**
     * 计算 4-bit sampling_frequency_index(ISO/IEC 14496-3 Table 1.16)。
     * 未知采样率返回 15(reserved)—— 调用方应避免这种情况。
     */
    fun samplingFrequencyIndex(sampleRateHz: Int): Int = when (sampleRateHz) {
        96_000 -> 0
        88_200 -> 1
        64_000 -> 2
        48_000 -> 3
        44_100 -> 4
        32_000 -> 5
        24_000 -> 6
        22_050 -> 7
        16_000 -> 8
        12_000 -> 9
        11_025 -> 10
        8_000 -> 11
        7_350 -> 12
        else -> 15
    }

    /**
     * 用 ADTS 头包一个 raw AAC frame。
     */
    fun wrap(aacFrame: ByteArray, sampleRateHz: Int, channels: Int): ByteArray {
        val frameLen = aacFrame.size + 7
        val freqIdx = samplingFrequencyIndex(sampleRateHz)
        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte() // syncword low + MPEG-4 + no CRC
        header[2] = (((1 shl 6) or (freqIdx shl 2) or ((channels shr 2) and 0x01)) and 0xFF).toByte()
        header[3] = ((((channels and 0x03) shl 6) or ((frameLen shr 11) and 0x03)) and 0xFF).toByte()
        header[4] = ((frameLen shr 3) and 0xFF).toByte()
        header[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        val out = ByteArray(frameLen)
        header.copyInto(out, destinationOffset = 0)
        aacFrame.copyInto(out, destinationOffset = 7)
        return out
    }
}

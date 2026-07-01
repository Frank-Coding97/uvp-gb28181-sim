package com.uvp.sim.media

/**
 * Helpers for splitting / joining Annex-B encoded H.264 streams.
 * Used by both the muxer (where it concatenates NALs with start codes) and
 * the platform encoders (where MediaCodec / VideoToolbox emit Annex-B already
 * but with mixed 3- and 4-byte start codes).
 */
object AnnexB {

    /**
     * Split an Annex-B byte stream into individual NAL units (without start codes).
     * Tolerates both 3-byte (0x00 0x00 0x01) and 4-byte (0x00 0x00 0x00 0x01) start codes.
     */
    fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()  // offset, prefixLen
        var i = 0
        while (i < data.size - 2) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts += i to 3
                    i += 3
                    continue
                } else if (data[i + 2].toInt() == 0 && i + 3 < data.size && data[i + 3].toInt() == 1) {
                    starts += i to 4
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        val result = mutableListOf<ByteArray>()
        for (k in starts.indices) {
            val (off, prefix) = starts[k]
            val from = off + prefix
            val to = if (k + 1 < starts.size) starts[k + 1].first else data.size
            result += data.copyOfRange(from, to)
        }
        return result
    }

    /**
     * Split an AVCC (length-prefixed) byte stream into individual NAL units.
     *
     * AVCC layout: `[len:N-BE][NAL body ...][len:N-BE][NAL body ...] ...`
     * where N is [lengthPrefixSize] (typically 4 for VideoToolbox output).
     *
     * Used by iOS VideoToolbox — the container gives length prefix instead of
     * Annex-B start code. Kotlin/Common byte manipulation, no cinterop.
     *
     * Malformed input (truncated length / body exceeds buffer) returns whatever
     * NALs were parsed before the corruption; does not throw.
     */
    fun splitAvcc(data: ByteArray, lengthPrefixSize: Int = 4): List<ByteArray> {
        require(lengthPrefixSize in 1..4) { "AVCC length prefix must be 1..4 bytes" }
        val result = mutableListOf<ByteArray>()
        var i = 0
        while (i + lengthPrefixSize <= data.size) {
            var nalLen = 0
            for (k in 0 until lengthPrefixSize) {
                nalLen = (nalLen shl 8) or (data[i + k].toInt() and 0xFF)
            }
            i += lengthPrefixSize
            if (nalLen <= 0 || i + nalLen > data.size) break  // truncated / corrupt
            result += data.copyOfRange(i, i + nalLen)
            i += nalLen
        }
        return result
    }
}

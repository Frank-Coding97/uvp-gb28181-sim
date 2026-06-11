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
}

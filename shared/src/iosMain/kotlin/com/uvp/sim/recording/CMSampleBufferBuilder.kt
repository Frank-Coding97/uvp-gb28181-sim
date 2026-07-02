package com.uvp.sim.recording

import com.uvp.sim.media.NalType

internal class CMSampleBufferBuilder {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun observeParameterSets(nalUnits: List<ByteArray>) {
        nalUnits.forEach { nal ->
            if (nal.isEmpty()) return@forEach
            when (nal[0].toInt() and 0x1F) {
                NalType.SPS -> sps = nal.copyOf()
                NalType.PPS -> pps = nal.copyOf()
            }
        }
    }

    fun hasFormatDescriptionInputs(): Boolean =
        sps != null && pps != null

    fun buildAvccPayload(nalUnits: List<ByteArray>): ByteArray {
        val filtered = nalUnits.filter { it.isNotEmpty() }
        val totalSize = filtered.sumOf { 4 + it.size }
        val out = ByteArray(totalSize)
        var offset = 0
        filtered.forEach { nal ->
            val len = nal.size
            out[offset] = ((len ushr 24) and 0xFF).toByte()
            out[offset + 1] = ((len ushr 16) and 0xFF).toByte()
            out[offset + 2] = ((len ushr 8) and 0xFF).toByte()
            out[offset + 3] = (len and 0xFF).toByte()
            offset += 4
            nal.copyInto(out, offset)
            offset += nal.size
        }
        return out
    }
}

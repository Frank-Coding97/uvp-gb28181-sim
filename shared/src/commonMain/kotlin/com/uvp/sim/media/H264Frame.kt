package com.uvp.sim.media

/**
 * One frame's worth of H.264 NAL units, plus timing info.
 *
 * Each NAL unit is in **Annex B form** — i.e. the start code `0x00 0x00 0x00 0x01`
 * is **not** included in the bytes (the muxer adds it where needed). The first byte
 * of each [nalUnits] entry is the NAL header (type in low 5 bits).
 *
 * GB28181 PS muxing requires SPS (NAL type 7) and PPS (NAL type 8) to immediately
 * precede every IDR (NAL type 5). The platform encoder is expected to emit them
 * together when [isKeyFrame] is true.
 */
data class H264Frame(
    val nalUnits: List<ByteArray>,
    /** Presentation timestamp in microseconds. */
    val timestampUs: Long,
    val isKeyFrame: Boolean
) {
    fun nalTypes(): List<Int> = nalUnits.map { (it[0].toInt() and 0x1F) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is H264Frame) return false
        if (timestampUs != other.timestampUs) return false
        if (isKeyFrame != other.isKeyFrame) return false
        if (nalUnits.size != other.nalUnits.size) return false
        for (i in nalUnits.indices) {
            if (!nalUnits[i].contentEquals(other.nalUnits[i])) return false
        }
        return true
    }
    override fun hashCode(): Int {
        var r = timestampUs.hashCode()
        r = 31 * r + isKeyFrame.hashCode()
        for (n in nalUnits) r = 31 * r + n.contentHashCode()
        return r
    }
}

/** NAL unit type constants — Rec. ITU-T H.264 § 7.4.1. */
object NalType {
    const val NON_IDR = 1
    const val IDR = 5
    const val SEI = 6
    const val SPS = 7
    const val PPS = 8
}

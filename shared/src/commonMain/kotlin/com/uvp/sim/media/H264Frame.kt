package com.uvp.sim.media

/**
 * One frame of video,with its NAL units and timing info.
 *
 * Originally H.264-only — the name is kept for now to limit blast radius.
 * H.265 frames flow through the same shape: each [nalUnits] entry is the NAL
 * payload **without** the Annex-B start code (the muxer adds it).
 *
 * For H.264 the NAL type is the low 5 bits of byte 0; for H.265 it's
 * `(byte0 >> 1) & 0x3F`. Use [VideoCodec] + [VideoCodec.nalType] to read it.
 *
 * GB28181 PS muxing requires the codec's parameter sets (SPS/PPS for H.264,
 * VPS/SPS/PPS for H.265) to immediately precede every IDR. The platform
 * encoder is expected to emit them together when [isKeyFrame] is true.
 */
data class H264Frame(
    val nalUnits: List<ByteArray>,
    /** Presentation timestamp in microseconds. */
    val timestampUs: Long,
    val isKeyFrame: Boolean,
    val codec: VideoCodec = VideoCodec.H264
) {
    fun nalTypes(): List<Int> = nalUnits.map { codec.nalType(it[0]) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is H264Frame) return false
        if (timestampUs != other.timestampUs) return false
        if (isKeyFrame != other.isKeyFrame) return false
        if (codec != other.codec) return false
        if (nalUnits.size != other.nalUnits.size) return false
        for (i in nalUnits.indices) {
            if (!nalUnits[i].contentEquals(other.nalUnits[i])) return false
        }
        return true
    }
    override fun hashCode(): Int {
        var r = timestampUs.hashCode()
        r = 31 * r + isKeyFrame.hashCode()
        r = 31 * r + codec.hashCode()
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

/** NAL unit type constants — Rec. ITU-T H.265 § 7.4.2.2. */
object H265NalType {
    const val TRAIL_N = 0
    const val TRAIL_R = 1
    const val IDR_W_RADL = 19
    const val IDR_N_LP = 20
    const val CRA_NUT = 21
    const val VPS_NUT = 32
    const val SPS_NUT = 33
    const val PPS_NUT = 34
    const val AUD_NUT = 35
    const val SEI_PREFIX = 39
    const val SEI_SUFFIX = 40
}

/**
 * Codec hint carried with each frame so the muxer / RTP packer can pick the
 * right Program Stream stream_type and NAL header parser.
 *
 * Mirrors [com.uvp.sim.config.VideoCodec] but lives in the media layer so the
 * media module doesn't reach back into config. The bridge (Android shell)
 * maps one onto the other.
 */
enum class VideoCodec(val label: String, val psStreamType: Int) {
    /** ISO/IEC 13818-1 stream_type 0x1B for H.264/AVC video. */
    H264("H.264", 0x1B),

    /** ISO/IEC 13818-1 stream_type 0x24 for H.265/HEVC video. */
    H265("H.265", 0x24);

    /** Extract NAL unit type from the first byte of the NAL payload. */
    fun nalType(headerByte: Byte): Int = when (this) {
        H264 -> headerByte.toInt() and 0x1F
        H265 -> (headerByte.toInt() ushr 1) and 0x3F
    }

    /** True if this NAL type carries an instantaneous decoder refresh frame. */
    fun isKeyNal(nalType: Int): Boolean = when (this) {
        H264 -> nalType == NalType.IDR
        H265 -> nalType == H265NalType.IDR_W_RADL ||
            nalType == H265NalType.IDR_N_LP ||
            nalType == H265NalType.CRA_NUT
    }

    /** True if this NAL type is a parameter set that should precede every key frame. */
    fun isParameterSet(nalType: Int): Boolean = when (this) {
        H264 -> nalType == NalType.SPS || nalType == NalType.PPS
        H265 -> nalType == H265NalType.VPS_NUT ||
            nalType == H265NalType.SPS_NUT ||
            nalType == H265NalType.PPS_NUT
    }
}


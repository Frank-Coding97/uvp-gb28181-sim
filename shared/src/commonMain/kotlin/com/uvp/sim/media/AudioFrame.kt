package com.uvp.sim.media

/**
 * One frame of compressed audio for PS muxing.
 *
 * `payload` is the codec-specific encoded bytes — for G.711 it's raw A-law /
 * μ-law samples (1 byte = 1 sample), for AAC it's an ADTS-stripped raw frame.
 *
 * Timestamp is in microseconds, same clock as [H264Frame.timestampUs] so the
 * muxer can interleave them.
 */
data class AudioFrame(
    val payload: ByteArray,
    val timestampUs: Long,
    val codec: AudioCodec
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return timestampUs == other.timestampUs &&
            codec == other.codec &&
            payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int {
        var r = timestampUs.hashCode()
        r = 31 * r + codec.hashCode()
        r = 31 * r + payload.contentHashCode()
        return r
    }
}

/**
 * Audio codec selection — defines PS stream_type and sample rate / channel
 * info needed by the muxer and SDP layer.
 *
 * GB28181 § 10.1.1 commonly carries G.711A (PCMA) at 8kHz. AAC streams use
 * MPEG-2 stream_type 0x0F (ADTS AAC) which most players including ZLMediaKit
 * handle out of the box.
 */
enum class AudioCodec(
    val label: String,
    val psStreamType: Int,
    val sampleRateHz: Int,
    val channels: Int
) {
    /** ITU-T G.711 A-law, 8 kHz mono. PS stream_type 0x90 / payload type 8. */
    G711A("G.711A", 0x90, 8_000, 1),

    /** ITU-T G.711 μ-law, 8 kHz mono. PS stream_type 0x90 / payload type 0. */
    G711U("G.711U", 0x90, 8_000, 1),

    /** AAC (ADTS), 16 kHz mono. PS stream_type 0x0F. */
    AAC("AAC", 0x0F, 16_000, 1);
}

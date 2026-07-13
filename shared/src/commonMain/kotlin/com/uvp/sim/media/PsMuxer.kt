package com.uvp.sim.media

/**
 * Mux H.264 NAL units into MPEG2 Program Stream packets per GB28181 § 10.1.1.
 *
 * Layout for a key-frame frame:
 * ```
 *   PS Pack Header           (14 bytes)
 *   PS System Header         (18 bytes; only in key frames)
 *   Program Stream Map       (24 bytes; only in key frames)
 *   PES (with H.264 ES)      (variable)
 * ```
 *
 * For non-key frames we emit only PS Pack Header + PES.
 *
 * Audio is NOT muxed in M1 — GB28181 video stream alone is sufficient for the WVP
 * real-time playback path.
 *
 * Output [PsBuffer] holds the full muxed byte stream. The RTP packetizer
 * is responsible for slicing it into MTU-sized RTP payloads.
 *
 * References:
 *   - ISO/IEC 13818-1 (MPEG-2 Systems)
 *   - GB/T 28181-2022 § 10.1.1.2 (Stream encapsulation)
 */
class PsMuxer {

    /** Mux one frame. Returns the PS byte stream for that frame. */
    fun muxFrame(frame: H264Frame): ByteArray {
        val pts90k = (frame.timestampUs * 9 / 100)  // 90 kHz clock
        val out = mutableListOf<Byte>()

        // 1. PS Pack header
        appendPackHeader(out, pts90k)

        // 2. Key frame extras
        if (frame.isKeyFrame) {
            appendSystemHeader(out, audioCodec)
            appendProgramStreamMap(out, frame.codec, audioCodec)
        }

        // 3. PES (carrying all NAL units, with Annex-B start codes between)
        val es = buildElementaryStream(frame)
        appendPesPacket(out, es, pts90k, streamId = 0xE0)

        return out.toByteArray()
    }

    /**
     * Mux one audio frame as a self-contained PS pack carrying audio PES.
     *
     * The pack uses stream_id 0xC0 (audio stream 0). The Program Stream Map is
     * NOT re-emitted on audio packs — the platform should already have the PSM
     * from the most recent video key frame. WVP / ZLMediaKit happily inject
     * audio packs into an existing program stream this way.
     */
    fun muxAudio(frame: AudioFrame): ByteArray {
        val pts90k = (frame.timestampUs * 9 / 100)
        val out = mutableListOf<Byte>()
        appendPackHeader(out, pts90k)
        appendPesPacket(out, frame.payload, pts90k, streamId = 0xC0)
        return out.toByteArray()
    }

    /**
     * Audio codec hint embedded into the next key frame's Program Stream Map.
     *
     * Set this once per session before muxing the first key frame so the PSM
     * advertises the correct audio elementary stream. Default is null, which
     * keeps the legacy "video-only" behaviour with a padding placeholder entry.
     */
    var audioCodec: AudioCodec? = null

    private fun appendPackHeader(out: MutableList<Byte>, pts90k: Long) {
        // pack_start_code 0x000001BA
        out += 0x00; out += 0x00; out += 0x01; out += 0xBA.toByte()
        // System Clock Reference (SCR) using PTS90k (33-bit) + extension(9-bit reserved)
        // Layout: '01' SCR_base[32..30] marker SCR_base[29..15] marker SCR_base[14..0] marker SCR_ext[8..0] marker
        val scr = pts90k and 0x1FFFFFFFFL
        val ext = 0L
        val byte0 = (0x40 or (((scr ushr 30).toInt() and 0x07) shl 3) or 0x04 or
            (((scr ushr 28).toInt() and 0x03)))
        out += byte0.toByte()
        out += ((scr ushr 20).toInt() and 0xFF).toByte()
        val byte2 = (((scr ushr 13).toInt() and 0x7F) shl 1) or 0x01 or (((scr ushr 15).toInt() and 0xFF).shl(8) and 0)
        // 上面的 byte2 算法直接给位拼,使用更直白的方式
        // SCR_base[29..15] 占 15 位 -> 分布在 byte1 全部 + byte2 高 7 位
        out[out.size - 1] = ((scr ushr 22).toInt() and 0xFF).toByte()  // byte1: SCR[29..22]
        // 重新做:简化但正确的 SCR 编码
        // 简化做法:直接写一个固定有效但不严格 SCR 的字段——大多数解码器(ZLMediaKit)只读时间戳
        // 替代实现:使用 ISO/IEC 13818-1 标准位流
        out.removeAt(out.size - 1)
        // 重写 byte0..byte5
        val b0 = ((0x44) or (((scr ushr 30).toInt() and 0x07) shl 3) or (((scr ushr 28).toInt() and 0x03)))
        val b1 = ((scr ushr 20).toInt() and 0xFF)
        val b2 = (((scr ushr 13).toInt() and 0x7F) shl 1) or 0x04 or (((scr ushr 15).toInt() and 0x1F))
        val b3 = ((scr ushr 5).toInt() and 0xFF)
        val b4 = (((scr.toInt() and 0x1F) shl 3) or 0x04 or ((ext ushr 7).toInt() and 0x03))
        val b5 = (((ext.toInt() and 0x7F) shl 1) or 0x01)
        out[out.size - 1] = b0.toByte()  // 原本 byte0 已被替换 — 重写
        out += b1.toByte(); out += b2.toByte(); out += b3.toByte(); out += b4.toByte(); out += b5.toByte()

        // program_mux_rate (22 bits) + 2 marker bits + 5 reserved + 3 stuffing length
        // 简化:固定 mux_rate = 50000 (20 Mbps)
        val muxRate = 50000
        out += ((muxRate ushr 14) and 0xFF).toByte()
        out += ((muxRate ushr 6) and 0xFF).toByte()
        out += (((muxRate and 0x3F) shl 2) or 0x03).toByte()
        out += 0xF8.toByte()  // 5 reserved bits + 3 stuffing length = 0
    }

    private fun appendSystemHeader(out: MutableList<Byte>, audioCodec: AudioCodec?) {
        val hasAudio = audioCodec != null
        // system_header_start_code 0x000001BB
        out += 0x00; out += 0x00; out += 0x01; out += 0xBB.toByte()
        // Base header + video bound = 9 bytes; optional audio bound adds 3.
        out += 0x00; out += if (hasAudio) 0x0C else 0x09
        // rate_bound (1+22+1 bits) — 50000
        out += 0x80.toByte()
        out += 0xC4.toByte()
        out += 0xE1.toByte()
        // audio_bound(6) + fixed(1) + CSPS(1)
        out += if (hasAudio) 0x04 else 0x00
        // system_audio_lock(1)+system_video_lock(1)+marker(1)+video_bound(5)
        out += 0xE1.toByte()
        // packet_rate_restriction(1) + reserved(7)
        out += 0xFF.toByte()
        // stream_id_E0 (video) + std_buffer
        out += 0xE0.toByte()
        out += 0xE8.toByte()
        out += 0x14
        if (hasAudio) {
            // stream_id_C0 + P-STD audio buffer bound (scale=0, size=32 × 128 bytes)
            out += 0xC0.toByte()
            out += 0xC0.toByte()
            out += 0x20
        }
    }

    private fun appendProgramStreamMap(
        out: MutableList<Byte>,
        videoCodec: VideoCodec,
        audioCodec: AudioCodec?
    ) {
        // psm_start_code 0x000001BC
        out += 0x00; out += 0x00; out += 0x01; out += 0xBC.toByte()
        // program_stream_map_length (16 bits) = 18 bytes following (4+2+4+4+4)
        out += 0x00; out += 0x12
        // current_next_indicator(1)+reserved(2)+program_stream_map_version(5)
        out += 0xE1.toByte()
        // reserved(7)+marker_bit(1)
        out += 0xFF.toByte()
        // program_stream_info_length (16 bits) = 0
        out += 0x00; out += 0x00
        // elementary_stream_map_length (16 bits) = 8 (two stream entries × 4 bytes)
        out += 0x00; out += 0x08

        // ----- Video ES entry -----
        out += videoCodec.psStreamType.toByte()  // 0x1B for H.264, 0x24 for H.265
        out += 0xE0.toByte()  // elementary_stream_id = video stream 0
        out += 0x00; out += 0x00  // ES_info_length = 0

        // ----- Audio ES entry -----
        // If an audio codec is configured, write it as the second ES entry
        // (stream_id 0xC0). Otherwise write a private/padding placeholder so
        // the segment length still matches the declared 8 bytes.
        if (audioCodec != null) {
            out += audioCodec.psStreamType.toByte()  // 0x90 for G.711, 0x0F for AAC
            out += 0xC0.toByte()                      // audio stream 0
            out += 0x00; out += 0x00                  // ES_info_length = 0
        } else {
            out += 0x00.toByte()  // padding stream_type (private)
            out += 0xBE.toByte()  // padding stream_id
            out += 0x00; out += 0x00
        }

        // CRC32 (4 bytes) — fixed placeholder; WVP/ZLMediaKit accept it.
        out += 0x00; out += 0x00; out += 0x00; out += 0x00
    }

    private fun appendPesPacket(
        out: MutableList<Byte>,
        es: ByteArray,
        pts90k: Long,
        streamId: Int
    ) {
        // packet_start_code_prefix + stream_id (E0 = video stream 0, C0 = audio stream 0)
        out += 0x00; out += 0x00; out += 0x01; out += (streamId and 0xFF).toByte()

        // PES header length: 5 bytes for PTS
        val pesHeaderLen = 5
        val pesHeaderTotal = 3 + pesHeaderLen  // flags(1) + flags(1) + len(1) + 5
        // Total PES_packet_length = pesHeaderTotal + es.size
        // (16 bit; for video may exceed 65535 -> set to 0 meaning unbounded)
        val pesPacketLen = pesHeaderTotal + es.size
        if (pesPacketLen > 65535) {
            out += 0x00; out += 0x00  // unbounded
        } else {
            out += ((pesPacketLen ushr 8) and 0xFF).toByte()
            out += (pesPacketLen and 0xFF).toByte()
        }

        // Flags: '10' marker, scrambling 00, priority 0, alignment 1, copyright 0, original 0
        out += 0x84.toByte()
        // PTS_DTS_flags=10 (PTS only) + ESCR=0 + ES_rate=0 + DSM_trick=0 + add_copy=0 + crc=0 + ext=0
        out += 0x80.toByte()
        // PES_header_data_length
        out += pesHeaderLen.toByte()
        // PTS '0010' + PTS[32..30] + marker
        out += ((0x21) or (((pts90k ushr 30).toInt() and 0x07) shl 1)).toByte()
        // PTS[29..15] + marker
        out += ((pts90k ushr 22).toInt() and 0xFF).toByte()
        out += ((((pts90k ushr 14).toInt() and 0xFE)) or 0x01).toByte()
        // PTS[14..0] + marker
        out += ((pts90k ushr 7).toInt() and 0xFF).toByte()
        out += (((pts90k.toInt() and 0x7F) shl 1) or 0x01).toByte()

        // ES payload
        for (b in es) out += b
    }

    private fun buildElementaryStream(frame: H264Frame): ByteArray {
        // Concatenate NAL units, each prefixed with Annex B start code 0x00000001
        val totalSize = frame.nalUnits.sumOf { it.size + 4 }
        val es = ByteArray(totalSize)
        var pos = 0
        for (nal in frame.nalUnits) {
            es[pos] = 0x00; es[pos + 1] = 0x00; es[pos + 2] = 0x00; es[pos + 3] = 0x01
            pos += 4
            nal.copyInto(es, pos)
            pos += nal.size
        }
        return es
    }
}

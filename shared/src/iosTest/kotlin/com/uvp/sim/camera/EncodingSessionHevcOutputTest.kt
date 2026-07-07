package com.uvp.sim.camera

import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.H265NalType
import com.uvp.sim.media.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-B1-4:验证 HEVC AVCC → NAL 切分 + 关键帧判定的 pure-Kotlin 部分。
 *
 * `toH265Frame` 本身依赖 CMSampleBufferRef,iosSimulator 无法便捷构造真 CMSampleBuffer,
 * 所以本 test 直接验其内部核心逻辑:
 *   - `AnnexB.splitAvcc(bytes, 4)` 对 HEVC AVCC 字节流的切分正确
 *   - HEVC NAL type 解析 `(byte0 >> 1) & 0x3F` 与 `VideoCodec.H265.isKeyNal` 组合
 *
 * 完整的 CMSampleBuffer → H264Frame 端到端在真机 T-B4-3 阶段验。
 */
class EncodingSessionHevcOutputTest {

    /**
     * 构造 HEVC AVCC 字节流:`[len:4B-BE][NAL body]` 拼接。
     * NAL 头第一字节 = `(nalType << 1) | 0x01`(F=0,layer_id=0,tid_plus1=1)。
     */
    private fun hevcNalBytes(nalType: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(((nalType shl 1) and 0x7E).toByte(), 0x01)
        return header + payload
    }

    private fun avccWrap(vararg nals: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        for (nal in nals) {
            val len = nal.size
            out += (len ushr 24).toByte()
            out += (len ushr 16).toByte()
            out += (len ushr 8).toByte()
            out += len.toByte()
            out += nal.toList()
        }
        return out.toByteArray()
    }

    @Test
    fun splitAvcc_hevc_key_frame_4nals_in_order() {
        val vps = hevcNalBytes(H265NalType.VPS_NUT, ByteArray(3) { 0xAA.toByte() })
        val sps = hevcNalBytes(H265NalType.SPS_NUT, ByteArray(5) { 0xBB.toByte() })
        val pps = hevcNalBytes(H265NalType.PPS_NUT, ByteArray(2) { 0xCC.toByte() })
        val idr = hevcNalBytes(H265NalType.IDR_W_RADL, ByteArray(20) { 0xDD.toByte() })

        // 通常 encoder 输出 body 只含 slice(VPS/SPS/PPS 从 formatDescription 拉出),这里
        // 构造 body = IDR slice
        val avcc = avccWrap(idr)
        val nals = AnnexB.splitAvcc(avcc, lengthPrefixSize = 4)

        assertEquals(1, nals.size, "body 只含 1 个 IDR slice")
        val t = (nals[0][0].toInt() ushr 1) and 0x3F
        assertEquals(H265NalType.IDR_W_RADL, t)
        assertTrue(VideoCodec.H265.isKeyNal(t), "IDR_W_RADL 应识别为 key frame")

        // 参数集单独 wrap 也能 split
        val paramsAvcc = avccWrap(vps, sps, pps)
        val paramNals = AnnexB.splitAvcc(paramsAvcc, lengthPrefixSize = 4)
        assertEquals(3, paramNals.size)
        assertEquals(H265NalType.VPS_NUT, (paramNals[0][0].toInt() ushr 1) and 0x3F)
        assertEquals(H265NalType.SPS_NUT, (paramNals[1][0].toInt() ushr 1) and 0x3F)
        assertEquals(H265NalType.PPS_NUT, (paramNals[2][0].toInt() ushr 1) and 0x3F)
    }

    @Test
    fun splitAvcc_hevc_p_frame_body_only_no_key() {
        // TRAIL_R slice = 非关键帧
        val trail = hevcNalBytes(H265NalType.TRAIL_R, ByteArray(30) { 0x11.toByte() })
        val avcc = avccWrap(trail)
        val nals = AnnexB.splitAvcc(avcc, lengthPrefixSize = 4)
        assertEquals(1, nals.size)
        val t = (nals[0][0].toInt() ushr 1) and 0x3F
        assertEquals(H265NalType.TRAIL_R, t)
        assertFalse(VideoCodec.H265.isKeyNal(t), "TRAIL_R 不应识别为 key frame")
    }

    @Test
    fun key_frame_detection_cra_nut_and_idr_variants() {
        assertTrue(VideoCodec.H265.isKeyNal(H265NalType.IDR_W_RADL))
        assertTrue(VideoCodec.H265.isKeyNal(H265NalType.IDR_N_LP))
        assertTrue(VideoCodec.H265.isKeyNal(H265NalType.CRA_NUT))
        assertFalse(VideoCodec.H265.isKeyNal(H265NalType.TRAIL_N))
        assertFalse(VideoCodec.H265.isKeyNal(H265NalType.TRAIL_R))
    }
}

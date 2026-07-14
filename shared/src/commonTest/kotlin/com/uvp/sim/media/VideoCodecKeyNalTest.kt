package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * cross-review v1.1 refactor C(feedVideoFrame 拆分)的新增测试:
 * 验证 `hasAnyKeyNal` 纯函数在 H.264 / H.265 codec 下正确识别 IDR / CRA / IRAP 帧,
 * 让 IosRecordingService.feedVideoFrame 的首帧 IDR gate 语义可独立测试(拆前依赖
 * 完整 AVAssetWriter 流程,不能在 simulator 上验行为等价)。
 *
 * 参考 [VideoCodec.isKeyNal] + [NalType] / [H265NalType]。
 */
class VideoCodecKeyNalTest {

    // ---------- H.264 ----------

    @Test
    fun h264_idr_slice_is_key() {
        // NAL header:forbidden_zero_bit=0, nal_ref_idc=3(0b11), nal_unit_type=5(IDR)
        val idrHeader: Byte = ((0b011 shl 5) or NalType.IDR).toByte()
        assertTrue(
            hasAnyKeyNal(listOf(byteArrayOf(idrHeader, 0x00)), VideoCodec.H264),
        )
    }

    @Test
    fun h264_non_idr_slice_is_not_key() {
        // nal_unit_type=1(non-IDR slice)
        val nonIdr: Byte = ((0b010 shl 5) or NalType.NON_IDR).toByte()
        assertFalse(hasAnyKeyNal(listOf(byteArrayOf(nonIdr, 0x00)), VideoCodec.H264))
    }

    @Test
    fun h264_sps_pps_alone_not_key() {
        // 参数集不算 key frame(判据要单独存在的 IDR slice)
        val sps: Byte = NalType.SPS.toByte()
        val pps: Byte = NalType.PPS.toByte()
        assertFalse(hasAnyKeyNal(listOf(byteArrayOf(sps), byteArrayOf(pps)), VideoCodec.H264))
    }

    @Test
    fun h264_mixed_sps_pps_idr_is_key() {
        val sps: Byte = NalType.SPS.toByte()
        val pps: Byte = NalType.PPS.toByte()
        val idrHeader: Byte = ((0b011 shl 5) or NalType.IDR).toByte()
        assertTrue(
            hasAnyKeyNal(
                listOf(byteArrayOf(sps), byteArrayOf(pps), byteArrayOf(idrHeader, 0x00)),
                VideoCodec.H264,
            ),
        )
    }

    // ---------- H.265 ----------

    /**
     * H.265 NAL header 前 2 字节:
     *   byte0: forbidden_zero_bit(1) + nal_unit_type(6) + nuh_layer_id 高位(1)
     *   byte1: nuh_layer_id 低 5 位 + nuh_temporal_id_plus1(3)
     * codec.nalType(byte0) = (byte0.toInt() shr 1) and 0x3F
     * 构造 header: (type shl 1) 即 nal_unit_type 落在 bit 1-6 的位置
     */
    private fun h265Header(type: Int): Byte = (type shl 1).toByte()

    @Test
    fun h265_idr_w_radl_is_key() {
        assertTrue(
            hasAnyKeyNal(
                listOf(byteArrayOf(h265Header(H265NalType.IDR_W_RADL), 0x01)),
                VideoCodec.H265,
            ),
        )
    }

    @Test
    fun h265_idr_n_lp_is_key() {
        assertTrue(
            hasAnyKeyNal(
                listOf(byteArrayOf(h265Header(H265NalType.IDR_N_LP), 0x01)),
                VideoCodec.H265,
            ),
        )
    }

    @Test
    fun h265_cra_nut_is_key() {
        assertTrue(
            hasAnyKeyNal(
                listOf(byteArrayOf(h265Header(H265NalType.CRA_NUT), 0x01)),
                VideoCodec.H265,
            ),
        )
    }

    @Test
    fun h265_vps_sps_pps_alone_not_key() {
        // 参数集不算 IRAP
        val vps = byteArrayOf(h265Header(H265NalType.VPS_NUT), 0x01)
        val sps = byteArrayOf(h265Header(H265NalType.SPS_NUT), 0x01)
        val pps = byteArrayOf(h265Header(H265NalType.PPS_NUT), 0x01)
        assertFalse(hasAnyKeyNal(listOf(vps, sps, pps), VideoCodec.H265))
    }

    @Test
    fun h265_trail_r_slice_not_key() {
        // trailing 图片是 non-IRAP
        val trailR = byteArrayOf(h265Header(1), 0x01)  // TRAIL_R = 1
        assertFalse(hasAnyKeyNal(listOf(trailR), VideoCodec.H265))
    }

    @Test
    fun h265_mixed_params_then_idr_is_key() {
        val vps = byteArrayOf(h265Header(H265NalType.VPS_NUT), 0x01)
        val sps = byteArrayOf(h265Header(H265NalType.SPS_NUT), 0x01)
        val pps = byteArrayOf(h265Header(H265NalType.PPS_NUT), 0x01)
        val idr = byteArrayOf(h265Header(H265NalType.IDR_W_RADL), 0x01)
        assertTrue(hasAnyKeyNal(listOf(vps, sps, pps, idr), VideoCodec.H265))
    }

    // ---------- edge ----------

    @Test
    fun empty_nal_list_not_key() {
        assertFalse(hasAnyKeyNal(emptyList(), VideoCodec.H264))
        assertFalse(hasAnyKeyNal(emptyList(), VideoCodec.H265))
    }

    @Test
    fun empty_byte_nal_skipped() {
        // 单个空 NAL byte array 不 crash,视为非 key
        assertFalse(hasAnyKeyNal(listOf(byteArrayOf()), VideoCodec.H264))
        assertFalse(hasAnyKeyNal(listOf(byteArrayOf()), VideoCodec.H265))
    }
}

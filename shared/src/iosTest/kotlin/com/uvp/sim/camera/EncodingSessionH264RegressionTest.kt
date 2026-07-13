package com.uvp.sim.camera

import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.NalType
import com.uvp.sim.media.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-B1-5:H.264 分支回归。
 *
 * T-B1-4 引入 `toEncodedFrame(sample, codec)` 分派 + `toH265Frame`。H.264 路径不改动逻辑,
 * 但需要一层单元回归保证 refactor 未误伤 —— 断言 pure-Kotlin 的 AVCC → NAL split + key frame
 * 判定语义与 v1.2 baseline 一致。
 *
 * 与 v1.2 IosCameraStreamer 单元 test 语义等价(v1.2 时期是黑盒,回归靠真机;本 test 显式化
 * pure-Kotlin 部分)。
 */
class EncodingSessionH264RegressionTest {

    /**
     * 构造 H.264 NAL:第一字节低 5 位 = nal_unit_type,前 3 位是 forbidden_zero(0)+
     * nal_ref_idc(3)。用 3 表示 IDR、参数集这类重要 NAL。
     */
    private fun h264NalBytes(nalType: Int, payload: ByteArray, nalRefIdc: Int = 3): ByteArray {
        val header = byteArrayOf((((nalRefIdc and 0x03) shl 5) or (nalType and 0x1F)).toByte())
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
    fun splitAvcc_h264_idr_slice_body() {
        val idr = h264NalBytes(NalType.IDR, ByteArray(20) { 0xAA.toByte() })
        val avcc = avccWrap(idr)
        val nals = AnnexB.splitAvcc(avcc, lengthPrefixSize = 4)
        assertEquals(1, nals.size)
        val t = nals[0][0].toInt() and 0x1F
        assertEquals(NalType.IDR, t)
        assertTrue(VideoCodec.H264.isKeyNal(t))
    }

    @Test
    fun splitAvcc_h264_p_slice_not_key() {
        val nonIdr = h264NalBytes(NalType.NON_IDR, ByteArray(50) { 0x22.toByte() })
        val avcc = avccWrap(nonIdr)
        val nals = AnnexB.splitAvcc(avcc, lengthPrefixSize = 4)
        assertEquals(1, nals.size)
        assertFalse(VideoCodec.H264.isKeyNal(nals[0][0].toInt() and 0x1F))
    }

    @Test
    fun sps_pps_idr_order_via_avcc() {
        // encoder 单次 emit body 通常只含 slice。SPS/PPS 从 formatDescription 拿。
        // 这里 assert splitAvcc 顺序稳定即可
        val sps = h264NalBytes(NalType.SPS, ByteArray(10) { 0x33.toByte() })
        val pps = h264NalBytes(NalType.PPS, ByteArray(4) { 0x44.toByte() })
        val idr = h264NalBytes(NalType.IDR, ByteArray(30) { 0x55.toByte() })

        val avcc = avccWrap(sps, pps, idr)
        val nals = AnnexB.splitAvcc(avcc, lengthPrefixSize = 4)
        assertEquals(3, nals.size)
        assertEquals(NalType.SPS, nals[0][0].toInt() and 0x1F)
        assertEquals(NalType.PPS, nals[1][0].toInt() and 0x1F)
        assertEquals(NalType.IDR, nals[2][0].toInt() and 0x1F)
    }
}

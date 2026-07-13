package com.uvp.sim.recording

import com.uvp.sim.media.H265NalType
import com.uvp.sim.media.NalType
import com.uvp.sim.media.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 守护 [CMSampleBufferBuilder] H.264 / H.265 双通路 NAL 解析 + 参数集识别的分派逻辑。
 *
 * 2026-07-13 老板真机报告 iOS H.265 录像永远 didWriteAny=false,根因是 builder
 * 硬编码 `& 0x1F == SPS`(H.264 mask),对 H.265 永远匹配不到 → format description
 * 建不出 → 每帧 dropMissingFormat。本类锁住:
 *
 *   - `observeParameterSets` 按 codec 走对的 NAL header 解析
 *   - `hasFormatDescriptionInputs` 阈值(H.264 两件套 / H.265 三件套)
 *   - `buildAvccPayload` 按 codec 过滤对的参数集类型
 *   - `configureCodec` 切换后旧的参数集因 reset 已清空(不跨会话污染)
 *
 * 不测 `buildVideoSampleBuffer`(要真 CMBlockBuffer + 真 SPS/PPS 才能过 CoreMedia
 * 校验,那属于集成/真机测试范畴)。
 */
class CMSampleBufferBuilderCodecTest {

    // ---- H.264 通路 ----

    @Test
    fun h264_recognizesSpsAndPps() {
        val b = CMSampleBufferBuilder()
        b.configureCodec(VideoCodec.H264)
        assertFalse(b.hasFormatDescriptionInputs(), "初始状态无参数集")

        val sps = h264Nal(NalType.SPS, extraSize = 20)
        val pps = h264Nal(NalType.PPS, extraSize = 4)
        b.observeParameterSets(listOf(sps, pps))
        assertTrue(b.hasFormatDescriptionInputs(), "SPS+PPS 齐后 hasFormatDescription=true")
    }

    @Test
    fun h264_ignoresH265ParameterSets() {
        // 老板 bug 的核心场景:H.264 builder 拿到 H.265 NAL,应当**全部忽略**,
        // hasFormatDescription 保持 false —— 不能误当 slice/IDR 让 writer 起录。
        val b = CMSampleBufferBuilder()
        b.configureCodec(VideoCodec.H264)

        // 构造 H.265 VPS(NAL type = 32):header 首字节 (32 << 1) = 0x40
        val hevcVps = byteArrayOf(0x40.toByte(), 0x01) + ByteArray(10)
        val hevcSps = byteArrayOf(0x42.toByte(), 0x01) + ByteArray(20)  // (33 << 1)
        val hevcPps = byteArrayOf(0x44.toByte(), 0x01) + ByteArray(4)   // (34 << 1)
        b.observeParameterSets(listOf(hevcVps, hevcSps, hevcPps))

        assertFalse(
            b.hasFormatDescriptionInputs(),
            "H.264 builder 拿到 H.265 NAL 时,参数集必须一个都不该被识别",
        )
    }

    @Test
    fun h264_avccPayloadStripsSpsAndPps() {
        val b = CMSampleBufferBuilder()
        b.configureCodec(VideoCodec.H264)

        val sps = h264Nal(NalType.SPS, extraSize = 5)
        val pps = h264Nal(NalType.PPS, extraSize = 3)
        val idr = h264Nal(NalType.IDR, extraSize = 100)

        val payload = b.buildAvccPayload(listOf(sps, pps, idr))
        // 只应保留 1 个 NAL(IDR),每个 NAL 前 4 字节长度 header
        assertEquals(4 + idr.size, payload.size, "SPS/PPS 应被过滤,仅保留 IDR")
    }

    // ---- H.265 通路 ----

    @Test
    fun h265_requiresVpsSpsPpsTriad() {
        val b = CMSampleBufferBuilder()
        b.configureCodec(VideoCodec.H265)
        assertFalse(b.hasFormatDescriptionInputs())

        val vps = h265Nal(H265NalType.VPS_NUT, extraSize = 8)
        val sps = h265Nal(H265NalType.SPS_NUT, extraSize = 20)
        val pps = h265Nal(H265NalType.PPS_NUT, extraSize = 4)

        // 缺 VPS
        b.observeParameterSets(listOf(sps, pps))
        assertFalse(b.hasFormatDescriptionInputs(), "缺 VPS 三件套不全")

        // 补上 VPS
        b.observeParameterSets(listOf(vps))
        assertTrue(b.hasFormatDescriptionInputs(), "VPS+SPS+PPS 三件套齐 → true")
    }

    @Test
    fun h265_ignoresH264Sps7WhichCollidesInLow5Bits() {
        // 微妙陷阱:H.264 SPS = 7,H.265 CRA_NUT = 21;低 5 bits 不一样(7 vs 21),
        // 但如果错用 `& 0x1F` 掩码把 H.264 SPS 送进 H.265 builder,builder 应正确忽略
        // (H.265 sps 是 NAL type 33)。这里断言 H.265 builder 收到 H.264 SPS 不响应。
        val b = CMSampleBufferBuilder()
        b.configureCodec(VideoCodec.H265)

        val h264Sps = h264Nal(NalType.SPS, extraSize = 20)
        b.observeParameterSets(listOf(h264Sps))
        assertFalse(
            b.hasFormatDescriptionInputs(),
            "H.265 builder 拿到 H.264 SPS 不应识别为 H.265 SPS",
        )
    }

    @Test
    fun h265_avccPayloadStripsVpsSpsPps() {
        val b = CMSampleBufferBuilder()
        b.configureCodec(VideoCodec.H265)

        val vps = h265Nal(H265NalType.VPS_NUT, extraSize = 8)
        val sps = h265Nal(H265NalType.SPS_NUT, extraSize = 12)
        val pps = h265Nal(H265NalType.PPS_NUT, extraSize = 4)
        val idr = h265Nal(H265NalType.IDR_W_RADL, extraSize = 100)

        val payload = b.buildAvccPayload(listOf(vps, sps, pps, idr))
        assertEquals(4 + idr.size, payload.size, "VPS/SPS/PPS 三件套应被过滤,仅保留 IDR")
    }

    // ---- 会话切换 ----

    @Test
    fun reset_clearsAccumulatedParameterSetsAcrossSessions() {
        val b = CMSampleBufferBuilder()
        b.configureCodec(VideoCodec.H265)
        b.observeParameterSets(listOf(
            h265Nal(H265NalType.VPS_NUT, extraSize = 8),
            h265Nal(H265NalType.SPS_NUT, extraSize = 20),
            h265Nal(H265NalType.PPS_NUT, extraSize = 4),
        ))
        assertTrue(b.hasFormatDescriptionInputs())

        b.reset()
        assertFalse(b.hasFormatDescriptionInputs(), "reset 后必须清零所有参数集")
    }

    @Test
    fun configureCodec_updatesActiveCodec() {
        val b = CMSampleBufferBuilder()
        assertEquals(VideoCodec.H264, b.activeCodec(), "默认 H.264")
        b.configureCodec(VideoCodec.H265)
        assertEquals(VideoCodec.H265, b.activeCodec())
    }

    // ---- helpers ----

    /**
     * 构造一个 H.264 NAL 单元。首字节低 5 bits = nalType,高 3 bits 是 forbidden_zero(0) +
     * nal_ref_idc(0b11 = 3, 高优先级参考帧,SPS/PPS 常用值)。
     */
    private fun h264Nal(nalType: Int, extraSize: Int): ByteArray {
        val header = ((3 shl 5) or (nalType and 0x1F)).toByte()
        return byteArrayOf(header) + ByteArray(extraSize) { it.toByte() }
    }

    /**
     * 构造一个 H.265 NAL 单元。首字节格式:forbidden_zero_bit(1) + nal_unit_type(6) + layer_id(1 高位)。
     * type 存在 bit 1-6,所以左移 1 位。第二字节是 layer_id 剩余 5 bit + tid_plus1(3 bit)。
     */
    private fun h265Nal(nalType: Int, extraSize: Int): ByteArray {
        val byte0 = ((nalType and 0x3F) shl 1).toByte()
        val byte1 = 0x01.toByte()  // layer_id=0, tid_plus1=1
        return byteArrayOf(byte0, byte1) + ByteArray(extraSize) { it.toByte() }
    }
}

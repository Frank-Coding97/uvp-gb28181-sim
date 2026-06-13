package com.uvp.sim.recording

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mp4SampleParser 自适应识别测试。
 *
 * 这是 2026-06-13 真机踩坑后补的 — CameraX Recorder 在某些机型写出的 mp4 sample
 * 用 AnnexB 起始码,而不是 AVCC 长度前缀。当时单测漏了这个,真机 PLAYBACK 推流
 * 第一帧只有 1 字节 NAL,WVP 解码不出画面。
 */
class Mp4SampleParserTest {

    private fun nal(vararg bytes: Int): ByteArray = bytes.map { it.toByte() }.toByteArray()

    // ---- AVCC ----

    @Test fun toNalList_avccTwoNals_splitsCorrectly() {
        // [00 00 00 03][67 42 80] [00 00 00 04][68 CE 38 80]
        val sample = nal(
            0x00, 0x00, 0x00, 0x03, 0x67, 0x42, 0x80,
            0x00, 0x00, 0x00, 0x04, 0x68, 0xCE, 0x38, 0x80
        )
        val nals = Mp4SampleParser.toNalList(sample)
        assertEquals(2, nals.size)
        assertContentEquals(nal(0x67, 0x42, 0x80), nals[0])
        assertContentEquals(nal(0x68, 0xCE, 0x38, 0x80), nals[1])
    }

    @Test fun toNalList_avccLargeIdr_keepsFullPayload() {
        // 模拟 2KB IDR sample
        val nalSize = 2048
        val sample = ByteArray(4 + nalSize)
        sample[0] = 0; sample[1] = 0; sample[2] = (nalSize ushr 8).toByte(); sample[3] = (nalSize and 0xFF).toByte()
        for (i in 0 until nalSize) sample[4 + i] = (i and 0xFF).toByte()
        val nals = Mp4SampleParser.toNalList(sample)
        assertEquals(1, nals.size)
        assertEquals(nalSize, nals[0].size)
    }

    // ---- AnnexB(回归 2026-06-13 真机踩坑) ----

    @Test fun toNalList_annexB4ByteStart_splitsCorrectly() {
        // 这就是当年的 bug 现场:首 4 字节 00 00 00 01,长度方法会把它读成 len=1
        // 丢掉后续整个 IDR 数据。修了之后这条 case 必须过。
        val sample = nal(
            0x00, 0x00, 0x00, 0x01, 0x65, 0xB8, 0x41, 0x7F,  // IDR slice (4B SC + 4B data)
            0x00, 0x00, 0x00, 0x01, 0x06, 0x00                // SEI (4B SC + 2B data)
        )
        val nals = Mp4SampleParser.toNalList(sample)
        assertEquals(2, nals.size)
        assertContentEquals(nal(0x65, 0xB8, 0x41, 0x7F), nals[0])
        assertContentEquals(nal(0x06, 0x00), nals[1])
    }

    @Test fun toNalList_annexB3ByteStart_splitsCorrectly() {
        // 3 字节起始码也要支持
        val sample = nal(
            0x00, 0x00, 0x01, 0x67, 0x42, 0x80,
            0x00, 0x00, 0x01, 0x68, 0xCE
        )
        val nals = Mp4SampleParser.toNalList(sample)
        assertEquals(2, nals.size)
        assertContentEquals(nal(0x67, 0x42, 0x80), nals[0])
        assertContentEquals(nal(0x68, 0xCE), nals[1])
    }

    @Test fun toNalList_annexBLargeIdr_keepsFullPayload() {
        // 真机 IDR ~140KB,关键回归
        val nalSize = 140_000
        val sample = ByteArray(4 + nalSize)
        sample[0] = 0; sample[1] = 0; sample[2] = 0; sample[3] = 1
        for (i in 0 until nalSize) sample[4 + i] = (i and 0xFF).toByte()
        val nals = Mp4SampleParser.toNalList(sample)
        assertEquals(1, nals.size)
        assertEquals(nalSize, nals[0].size)
    }

    // ---- 边界 ----

    @Test fun toNalList_tooShort_returnsEmpty() {
        assertTrue(Mp4SampleParser.toNalList(nal(0x00)).isEmpty())
        assertTrue(Mp4SampleParser.toNalList(ByteArray(0)).isEmpty())
    }

    @Test fun toNalList_avccCorrupted_returnsParsedPrefix() {
        // 第一个 length=10 但实际只有 3 字节,应该返回空 list 不抛
        val sample = nal(0x00, 0x00, 0x00, 0x0A, 0x67, 0x42, 0x80)
        val nals = Mp4SampleParser.toNalList(sample)
        assertTrue(nals.isEmpty())
    }

    // ---- stripStartCode ----

    @Test fun stripStartCode_4byteStart_removed() {
        val out = Mp4SampleParser.stripStartCode(nal(0x00, 0x00, 0x00, 0x01, 0x67, 0x42))
        assertContentEquals(nal(0x67, 0x42), out)
    }

    @Test fun stripStartCode_3byteStart_removed() {
        val out = Mp4SampleParser.stripStartCode(nal(0x00, 0x00, 0x01, 0x67, 0x42))
        assertContentEquals(nal(0x67, 0x42), out)
    }

    @Test fun stripStartCode_noStartCode_unchanged() {
        val out = Mp4SampleParser.stripStartCode(nal(0x67, 0x42, 0x80))
        assertContentEquals(nal(0x67, 0x42, 0x80), out)
    }
}

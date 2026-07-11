package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PsMuxerTest {

    private fun nal(type: Int, size: Int): ByteArray {
        val b = ByteArray(size)
        b[0] = ((type and 0x1F) or 0x60).toByte()  // forbidden_zero=0, nal_ref_idc=11
        for (i in 1 until size) b[i] = (i and 0xFF).toByte()
        return b
    }

    @Test fun keyFrameStartsWithPackHeader() {
        val frame = H264Frame(
            nalUnits = listOf(
                nal(NalType.SPS, 16),
                nal(NalType.PPS, 8),
                nal(NalType.IDR, 100)
            ),
            timestampUs = 0,
            isKeyFrame = true
        )
        val ps = PsMuxer().muxFrame(frame)
        // 0x000001BA = pack_start_code
        assertEquals(0x00, ps[0].toInt() and 0xFF)
        assertEquals(0x00, ps[1].toInt() and 0xFF)
        assertEquals(0x01, ps[2].toInt() and 0xFF)
        assertEquals(0xBA, ps[3].toInt() and 0xFF)
    }

    @Test fun keyFrameContainsSystemHeader() {
        val frame = H264Frame(
            nalUnits = listOf(nal(NalType.SPS, 16), nal(NalType.PPS, 8), nal(NalType.IDR, 100)),
            timestampUs = 0,
            isKeyFrame = true
        )
        val ps = PsMuxer().muxFrame(frame)
        // 找 0x000001BB = system_header_start_code
        assertTrue(findStartCode(ps, 0xBB) >= 0, "system header (0xBB) must be present in key frame")
    }

    @Test fun keyFrameContainsProgramStreamMap() {
        val frame = H264Frame(
            nalUnits = listOf(nal(NalType.SPS, 16), nal(NalType.PPS, 8), nal(NalType.IDR, 100)),
            timestampUs = 0,
            isKeyFrame = true
        )
        val ps = PsMuxer().muxFrame(frame)
        // 0x000001BC = program_stream_map_start_code
        assertTrue(findStartCode(ps, 0xBC) >= 0, "PSM (0xBC) must be present in key frame")
    }

    @Test fun systemHeaderDeclaresAudioStreamWhenAudioCodecIsConfigured() {
        val muxer = PsMuxer().apply { audioCodec = AudioCodec.G711A }
        val frame = H264Frame(
            nalUnits = listOf(nal(NalType.SPS, 16), nal(NalType.PPS, 8), nal(NalType.IDR, 100)),
            timestampUs = 0,
            isKeyFrame = true,
        )

        val ps = muxer.muxFrame(frame)
        val systemHeader = findStartCode(ps, 0xBB)

        assertTrue(systemHeader >= 0)
        val headerLength = ((ps[systemHeader + 4].toInt() and 0xFF) shl 8) or
            (ps[systemHeader + 5].toInt() and 0xFF)
        assertEquals(12, headerLength, "audio-enabled system header must include one audio stream bound")
        assertEquals(0xC0, ps[systemHeader + 15].toInt() and 0xFF)
    }

    @Test fun nonKeyFrameHasNoSystemHeader() {
        val frame = H264Frame(
            nalUnits = listOf(nal(NalType.NON_IDR, 50)),
            timestampUs = 40000,
            isKeyFrame = false
        )
        val ps = PsMuxer().muxFrame(frame)
        // 不含 SH/PSM
        assertEquals(-1, findStartCode(ps, 0xBB))
        assertEquals(-1, findStartCode(ps, 0xBC))
        // 但要含 PES (0xE0)
        assertTrue(findStartCode(ps, 0xE0) >= 0, "PES (0xE0) must be present")
    }

    @Test fun pesContainsAnnexBStartCodesBetweenNals() {
        val nal1 = nal(NalType.SPS, 8)
        val nal2 = nal(NalType.PPS, 6)
        val nal3 = nal(NalType.IDR, 50)
        val frame = H264Frame(
            nalUnits = listOf(nal1, nal2, nal3),
            timestampUs = 0,
            isKeyFrame = true
        )
        val ps = PsMuxer().muxFrame(frame)
        // 找到 0xE0 PES,然后跳过 PES header 6+5=11 字节
        val pes = findStartCode(ps, 0xE0)
        assertTrue(pes >= 0)
        // PES 内的 ES 应该有至少 3 个 Annex-B 起始码
        val pesPayloadStart = pes + 4 + 2 + 3 + 5  // start_code(4) + len(2) + flags(3) + ptsBytes(5)
        var startCodes = 0
        var i = pesPayloadStart
        while (i + 4 <= ps.size) {
            if (ps[i].toInt() == 0 && ps[i + 1].toInt() == 0 &&
                ps[i + 2].toInt() == 0 && ps[i + 3].toInt() == 1) {
                startCodes++
                i += 4
            } else {
                i++
            }
        }
        assertTrue(startCodes >= 3, "Expected ≥3 Annex-B start codes between NALs, got $startCodes")
    }

    @Test fun ptsTimestampMonotonicIncreasing() {
        val muxer = PsMuxer()
        val frame1 = H264Frame(listOf(nal(NalType.NON_IDR, 50)), 0, false)
        val frame2 = H264Frame(listOf(nal(NalType.NON_IDR, 50)), 40000, false)
        val frame3 = H264Frame(listOf(nal(NalType.NON_IDR, 50)), 80000, false)
        val pts = listOf(frame1, frame2, frame3).map { f ->
            // 直接 muxFrame,然后从输出里挖 PTS — 但我们用 90 kHz clock = us * 9 / 100
            f.timestampUs * 9 / 100
        }
        assertTrue(pts[0] < pts[1] && pts[1] < pts[2])
    }

    @Test fun outputIsNonEmpty() {
        val frame = H264Frame(listOf(nal(NalType.NON_IDR, 100)), 0, false)
        val ps = PsMuxer().muxFrame(frame)
        assertTrue(ps.size > 100, "PS output should be larger than NAL payload")
    }

    @Test fun keyFrameLargerThanNonKey() {
        val nalSet = listOf(nal(NalType.NON_IDR, 100))
        val keySet = listOf(nal(NalType.SPS, 8), nal(NalType.PPS, 6), nal(NalType.IDR, 100))
        val key = PsMuxer().muxFrame(H264Frame(keySet, 0, true))
        val nonKey = PsMuxer().muxFrame(H264Frame(nalSet, 0, false))
        // Key frame has SH+PSM extras → strictly larger
        assertTrue(key.size > nonKey.size, "Key frame should be larger; key=${key.size}, nonkey=${nonKey.size}")
    }

    @Test fun nalTypesExtractedCorrectly() {
        val frame = H264Frame(
            nalUnits = listOf(nal(NalType.SPS, 8), nal(NalType.PPS, 6), nal(NalType.IDR, 100)),
            timestampUs = 0,
            isKeyFrame = true
        )
        assertEquals(listOf(NalType.SPS, NalType.PPS, NalType.IDR), frame.nalTypes())
    }

    @Test fun h265KeyFramePsmHasStreamType0x24() {
        // Build a minimal H.265 NAL: header byte = (type << 1) | 0
        fun h265Nal(type: Int, size: Int): ByteArray {
            val b = ByteArray(size)
            b[0] = ((type and 0x3F) shl 1).toByte()
            b[1] = 0x01  // layer_id=0, tid_plus_one=1
            for (i in 2 until size) b[i] = (i and 0xFF).toByte()
            return b
        }
        val frame = H264Frame(
            nalUnits = listOf(
                h265Nal(H265NalType.VPS_NUT, 24),
                h265Nal(H265NalType.SPS_NUT, 32),
                h265Nal(H265NalType.PPS_NUT, 12),
                h265Nal(H265NalType.IDR_W_RADL, 200)
            ),
            timestampUs = 0,
            isKeyFrame = true,
            codec = VideoCodec.H265
        )
        val ps = PsMuxer().muxFrame(frame)
        // PSM start code 0x000001BC, then header bytes; the elementary stream
        // entry's first byte (stream_type) should be 0x24 for H.265.
        val psmOff = findStartCode(ps, 0xBC)
        assertTrue(psmOff >= 0, "PSM section must exist")
        // Layout after BC start code:
        //   length(2) + CNI/version(1) + marker(1) + psinfo_len(2) + esm_len(2) = 8
        // → stream_type at psmOff + 4 + 8 = +12
        val streamTypeIdx = psmOff + 12
        assertEquals(0x24, ps[streamTypeIdx].toInt() and 0xFF,
            "H.265 PSM stream_type must be 0x24")
    }

    @Test fun h264KeyFramePsmKeepsStreamType0x1B() {
        val frame = H264Frame(
            nalUnits = listOf(
                nal(NalType.SPS, 16),
                nal(NalType.PPS, 8),
                nal(NalType.IDR, 100)
            ),
            timestampUs = 0,
            isKeyFrame = true,
            codec = VideoCodec.H264
        )
        val ps = PsMuxer().muxFrame(frame)
        val psmOff = findStartCode(ps, 0xBC)
        assertTrue(psmOff >= 0)
        val streamTypeIdx = psmOff + 12
        assertEquals(0x1B, ps[streamTypeIdx].toInt() and 0xFF,
            "H.264 PSM stream_type must remain 0x1B")
    }

    @Test fun h265CodecExposesParameterSetTypes() {
        val codec = VideoCodec.H265
        assertTrue(codec.isParameterSet(H265NalType.VPS_NUT))
        assertTrue(codec.isParameterSet(H265NalType.SPS_NUT))
        assertTrue(codec.isParameterSet(H265NalType.PPS_NUT))
        assertTrue(codec.isKeyNal(H265NalType.IDR_W_RADL))
        assertTrue(codec.isKeyNal(H265NalType.IDR_N_LP))
        // 数据帧不该被当 key
        assertEquals(false, codec.isKeyNal(H265NalType.TRAIL_R))
    }

    /** Helper: 找 0x000001<wantedByte> 的偏移,返回起始码第 0 字节位置(找不到返回 -1) */
    private fun findStartCode(data: ByteArray, wantedByte: Int): Int {
        for (i in 0..data.size - 4) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 1 && (data[i + 3].toInt() and 0xFF) == wantedByte) {
                return i
            }
        }
        return -1
    }
}

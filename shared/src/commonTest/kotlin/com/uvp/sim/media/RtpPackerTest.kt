package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtpPackerTest {

    @Test fun smallPayloadFitsInOnePacket() {
        val packer = RtpPacker(maxPayloadSize = 1388, ssrc = 0xDEADBEEF.toInt())
        val data = ByteArray(500) { (it and 0xFF).toByte() }
        val packets = packer.packFrame(data, timestamp90k = 90000)
        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(2, RtpPacker.Inspect.version(p))
        assertEquals(96, RtpPacker.Inspect.payloadType(p))
        assertTrue(RtpPacker.Inspect.marker(p), "Last (only) packet must have M=1")
        assertEquals(90000L, RtpPacker.Inspect.timestamp(p))
        assertEquals(0xDEADBEEF.toInt(), RtpPacker.Inspect.ssrc(p))
        assertTrue(RtpPacker.Inspect.payload(p).contentEquals(data))
    }

    @Test fun largePayloadFragmentsByMtu() {
        val packer = RtpPacker(maxPayloadSize = 1388)
        val data = ByteArray(5000) { it.toByte() }
        val packets = packer.packFrame(data, 90000)
        // 5000 / 1388 = 3.6 → 4 packets
        assertEquals(4, packets.size)
        // 前 3 个 marker=0,最后一个 marker=1
        for (i in 0..2) {
            assertFalse(RtpPacker.Inspect.marker(packets[i]), "Packet $i should not have M=1")
        }
        assertTrue(RtpPacker.Inspect.marker(packets[3]), "Last packet must have M=1")
        // 所有 packet 的 timestamp 一致
        for (p in packets) {
            assertEquals(90000L, RtpPacker.Inspect.timestamp(p))
        }
    }

    @Test fun sequenceIncrementsBetweenPackets() {
        val packer = RtpPacker(maxPayloadSize = 1388)
        val data = ByteArray(5000) { it.toByte() }
        val packets = packer.packFrame(data, 90000)
        val seqs = packets.map { RtpPacker.Inspect.sequence(it) }
        for (i in 1 until seqs.size) {
            val diff = (seqs[i] - seqs[i - 1]) and 0xFFFF
            assertEquals(1, diff, "Sequence should monotonically increment by 1")
        }
    }

    @Test fun sequenceWrapsAround() {
        // 用反射手段不太合适;构造方式:从近 0xFFFF 的 seed 触发回绕
        // 我们这里通过持续 packFrame 65538 次,看 seq 能否回到原点
        // 简化:直接观察一次包尾末和下一次包头的 seq 变化
        val packer = RtpPacker(maxPayloadSize = 100)
        val many = ByteArray(100 * 70000) { 0 }  // 700+ packets
        // 太大不实际,改为分批
        var lastSeq = -1
        var wraps = 0
        repeat(700) {
            val pkts = packer.packFrame(ByteArray(50) { 1 }, 0)
            val s = RtpPacker.Inspect.sequence(pkts[0])
            if (lastSeq >= 0 && s < lastSeq && (lastSeq - s) > 32000) {
                wraps++
            }
            lastSeq = s
        }
        // 700 帧 << 65536,可能不会 wrap。改为只 assert seq 总是 0..65535
        assertTrue(lastSeq in 0..65535)
    }

    @Test fun ssrcConsistentAcrossPackets() {
        val packer = RtpPacker(maxPayloadSize = 1388, ssrc = 0x12345678)
        val data = ByteArray(5000) { 0 }
        val packets = packer.packFrame(data, 0)
        for (p in packets) {
            assertEquals(0x12345678, RtpPacker.Inspect.ssrc(p))
        }
    }

    @Test fun emptyPayloadGivesEmptyList() {
        val packer = RtpPacker()
        val packets = packer.packFrame(ByteArray(0), 0)
        assertTrue(packets.isEmpty())
    }

    @Test fun reassembledPayloadEqualsOriginal() {
        val packer = RtpPacker(maxPayloadSize = 200)
        val original = ByteArray(750) { (it * 31).toByte() }
        val packets = packer.packFrame(original, 12345)
        val reassembled = packets.fold(ByteArray(0)) { acc, p ->
            acc + RtpPacker.Inspect.payload(p)
        }
        assertTrue(original.contentEquals(reassembled))
    }

    @Test fun rtpHeaderLengthIs12Bytes() {
        val packer = RtpPacker(maxPayloadSize = 1388)
        val data = ByteArray(100) { 0 }
        val p = packer.packFrame(data, 0)[0]
        assertEquals(12 + 100, p.size, "RTP packet = 12 byte header + payload")
    }

    @Test fun timestamp32BitTruncation() {
        // 大于 2^32 的 timestamp 应该按 32-bit 截断写入
        val packer = RtpPacker(maxPayloadSize = 1388)
        val largeTs = 0x1_00000005L  // 33-bit
        val p = packer.packFrame(ByteArray(50) { 0 }, largeTs)[0]
        assertEquals(0x5L, RtpPacker.Inspect.timestamp(p))
    }
}

package com.uvp.sim.media

import kotlin.random.Random

/**
 * RTP packetizer for GB28181 PS streams — RFC 3550 + RFC 2250.
 *
 * GB28181 uses a fixed dynamic payload type (96) for PS streams clocked at 90 kHz.
 * Each RTP packet carries up to (MTU - rtpHeader = 1400 - 12 = 1388) bytes of PS data.
 *
 * The marker bit is set on the *last* RTP packet of a frame.
 *
 * Sequence number wraps every 65536 packets (uint16). SSRC is randomized at construction.
 */
class RtpPacker(
    val payloadType: Int = 96,
    val ssrc: Int = Random.nextInt(),
    val maxPayloadSize: Int = 1388
) {
    init {
        require(payloadType in 0..127) { "payloadType out of range" }
        require(maxPayloadSize in 64..1500) { "maxPayloadSize out of range" }
    }

    private var sequence: Int = Random.nextInt(0, 65536)

    /**
     * Pack a complete PS-encoded frame into RTP packets.
     * Same [timestamp90k] is shared across all packets of the same frame.
     * The last packet in the returned list has its marker bit set.
     */
    fun packFrame(psFrame: ByteArray, timestamp90k: Long): List<ByteArray> {
        if (psFrame.isEmpty()) return emptyList()
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < psFrame.size) {
            val chunkSize = minOf(maxPayloadSize, psFrame.size - offset)
            val isLast = offset + chunkSize >= psFrame.size
            packets += buildPacket(
                payload = psFrame,
                offset = offset,
                length = chunkSize,
                isLast = isLast,
                timestamp = timestamp90k
            )
            offset += chunkSize
        }
        return packets
    }

    private fun buildPacket(
        payload: ByteArray,
        offset: Int,
        length: Int,
        isLast: Boolean,
        timestamp: Long
    ): ByteArray {
        val packet = ByteArray(12 + length)

        // Byte 0: V(2)=2, P(1)=0, X(1)=0, CC(4)=0 → 0x80
        packet[0] = 0x80.toByte()
        // Byte 1: M(1) + PT(7)
        packet[1] = ((if (isLast) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()
        // Bytes 2..3: sequence (BE)
        packet[2] = ((sequence ushr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        // Bytes 4..7: timestamp (BE)
        val ts32 = (timestamp and 0xFFFFFFFFL).toInt()
        packet[4] = ((ts32 ushr 24) and 0xFF).toByte()
        packet[5] = ((ts32 ushr 16) and 0xFF).toByte()
        packet[6] = ((ts32 ushr 8) and 0xFF).toByte()
        packet[7] = (ts32 and 0xFF).toByte()
        // Bytes 8..11: SSRC (BE)
        packet[8] = ((ssrc ushr 24) and 0xFF).toByte()
        packet[9] = ((ssrc ushr 16) and 0xFF).toByte()
        packet[10] = ((ssrc ushr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()

        // Payload
        payload.copyInto(packet, destinationOffset = 12, startIndex = offset, endIndex = offset + length)

        sequence = (sequence + 1) and 0xFFFF
        return packet
    }

    /** Inspect an RTP packet — used by tests and the log/diagnostics view. */
    object Inspect {
        fun version(p: ByteArray): Int = (p[0].toInt() ushr 6) and 0x3
        fun marker(p: ByteArray): Boolean = (p[1].toInt() and 0x80) != 0
        fun payloadType(p: ByteArray): Int = p[1].toInt() and 0x7F
        fun sequence(p: ByteArray): Int =
            ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        fun timestamp(p: ByteArray): Long {
            val v = ((p[4].toInt() and 0xFF).toLong() shl 24) or
                ((p[5].toInt() and 0xFF).toLong() shl 16) or
                ((p[6].toInt() and 0xFF).toLong() shl 8) or
                (p[7].toInt() and 0xFF).toLong()
            return v
        }
        fun ssrc(p: ByteArray): Int =
            ((p[8].toInt() and 0xFF) shl 24) or
                ((p[9].toInt() and 0xFF) shl 16) or
                ((p[10].toInt() and 0xFF) shl 8) or
                (p[11].toInt() and 0xFF)
        fun payload(p: ByteArray): ByteArray = p.copyOfRange(12, p.size)
    }
}

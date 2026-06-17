package com.uvp.sim.network

/**
 * RFC 3550 RTP packet — fixed 12-byte header + payload.
 *
 * 语音广播下行(§9.8)接收侧:平台把 G.711 音频以 RTP/UDP 推给设备,设备解 12 字节头后
 * 取 payload 走 G.711 解码。[parse] 解析失败返回 null(接收循环里 inline-skip,不抛)。
 */
data class RtpPacket(
    val version: Int,
    val padding: Boolean,
    val extension: Boolean,
    val csrcCount: Int,
    val marker: Boolean,
    val payloadType: Int,
    val sequence: Int,
    val timestamp: Long,
    val ssrc: Long,             // unsigned 32-bit, 用 Long 存
    val payload: ByteArray
) {
    companion object {
        fun parse(bytes: ByteArray): RtpPacket? {
            if (bytes.size < 12) return null
            val b0 = bytes[0].toInt() and 0xFF
            val version = (b0 ushr 6) and 0x03
            if (version != 2) return null
            val padding = (b0 and 0x20) != 0
            val extension = (b0 and 0x10) != 0
            val csrcCount = b0 and 0x0F
            val b1 = bytes[1].toInt() and 0xFF
            val marker = (b1 and 0x80) != 0
            val payloadType = b1 and 0x7F
            val sequence = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            val timestamp = ((bytes[4].toInt() and 0xFF).toLong() shl 24) or
                ((bytes[5].toInt() and 0xFF).toLong() shl 16) or
                ((bytes[6].toInt() and 0xFF).toLong() shl 8) or
                (bytes[7].toInt() and 0xFF).toLong()
            val ssrc = ((bytes[8].toInt() and 0xFF).toLong() shl 24) or
                ((bytes[9].toInt() and 0xFF).toLong() shl 16) or
                ((bytes[10].toInt() and 0xFF).toLong() shl 8) or
                (bytes[11].toInt() and 0xFF).toLong()
            val headerLen = 12 + csrcCount * 4
            val payload = if (bytes.size > headerLen) bytes.copyOfRange(headerLen, bytes.size) else ByteArray(0)
            return RtpPacket(
                version, padding, extension, csrcCount, marker,
                payloadType, sequence, timestamp, ssrc, payload
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtpPacket) return false
        return version == other.version && padding == other.padding && extension == other.extension &&
            csrcCount == other.csrcCount && marker == other.marker && payloadType == other.payloadType &&
            sequence == other.sequence && timestamp == other.timestamp && ssrc == other.ssrc &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + payloadType
        r = 31 * r + sequence
        r = 31 * r + timestamp.hashCode()
        r = 31 * r + ssrc.hashCode()
        r = 31 * r + payload.contentHashCode()
        return r
    }
}

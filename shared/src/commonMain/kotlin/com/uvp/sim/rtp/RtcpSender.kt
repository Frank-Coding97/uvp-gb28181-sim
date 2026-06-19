package com.uvp.sim.rtp

/**
 * RTCP Sender Report (SR) 包构造 — RFC3550 § 6.4.1。
 *
 * 28 字节 minimal SR(无 receiver report block):
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +V|P|RC=0  | PT=200    |       length=6                        |   header (8 bytes)
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                            SSRC                                |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |              NTP timestamp, most significant word              |   sender info (20 bytes)
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |             NTP timestamp, least significant word              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         RTP timestamp                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     sender's packet count                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      sender's octet count                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * 用途:让平台的 QoS 监控有据可查。许多平台没收到 RTCP 会算"链路异常"。
 */
object RtcpSender {

    /** NTP epoch(1900-01-01)与 Unix epoch(1970-01-01)秒级差值。 */
    private const val NTP_EPOCH_OFFSET_SEC = 2_208_988_800L

    /**
     * 构造 28 字节 SR 包(big-endian)。
     *
     * @param ssrc                跟 RTP 流相同的 SSRC,big-endian 写入
     * @param ntpEpochMs          当前墙钟 epoch ms(平台校时基准下的)。会被转换为 NTP 64 位
     * @param rtpTimestamp        当前 RTP 时间戳(90kHz 视频时钟),低 32 位
     * @param senderPacketCount   累计发送 RTP 包数(unsigned 32 位)
     * @param senderOctetCount    累计发送字节数(payload only,unsigned 32 位)
     */
    fun buildSR(
        ssrc: Int,
        ntpEpochMs: Long,
        rtpTimestamp: Long,
        senderPacketCount: Long,
        senderOctetCount: Long
    ): ByteArray {
        val buf = ByteArray(28)
        // byte 0: V=2(0b10000000) | P=0 | RC=0
        buf[0] = 0x80.toByte()
        // byte 1: PT=200(SR)
        buf[1] = 200.toByte()
        // byte 2-3: length = 包字节数 / 4 - 1 = 28/4 - 1 = 6
        buf[2] = 0
        buf[3] = 6
        // byte 4-7: SSRC
        putUInt32(buf, 4, ssrc.toLong() and 0xFFFFFFFFL)

        // NTP 64 位:high32=自 1900-01-01 秒数,low32=小数部分
        val ntpSecs = (ntpEpochMs / 1000) + NTP_EPOCH_OFFSET_SEC
        val ntpFrac = ((ntpEpochMs % 1000) shl 32) / 1000
        putUInt32(buf, 8, ntpSecs and 0xFFFFFFFFL)
        putUInt32(buf, 12, ntpFrac and 0xFFFFFFFFL)

        // byte 16-19: RTP timestamp
        putUInt32(buf, 16, rtpTimestamp and 0xFFFFFFFFL)
        // byte 20-23: sender packet count(unsigned 32)
        putUInt32(buf, 20, senderPacketCount and 0xFFFFFFFFL)
        // byte 24-27: sender octet count(unsigned 32)
        putUInt32(buf, 24, senderOctetCount and 0xFFFFFFFFL)

        return buf
    }

    /** 把 unsigned 32(用 Long 承载)按 big-endian 写入 buf。 */
    private fun putUInt32(buf: ByteArray, offset: Int, v: Long) {
        buf[offset] = ((v ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (v and 0xFF).toByte()
    }
}

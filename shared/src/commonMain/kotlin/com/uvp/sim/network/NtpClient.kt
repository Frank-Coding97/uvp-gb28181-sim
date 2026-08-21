package com.uvp.sim.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.ByteReadPacket
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Instant

data class NtpSample(
    val instant: Instant,
    val offsetMillis: Long,
    val roundTripMillis: Long,
)

interface NtpClient {
    suspend fun query(server: String, port: Int = 123, timeoutMillis: Long = 1_500L): NtpSample
}

/** 单次 SNTP 查询。客户端只维护逻辑协议时钟，不修改系统时间。 */
class KtorNtpClient : NtpClient {
    override suspend fun query(server: String, port: Int, timeoutMillis: Long): NtpSample {
        require(server.isNotBlank()) { "NTP server is blank" }
        require(port in 1..65535) { "invalid NTP port: $port" }
        val selector = SelectorManager(Dispatchers.Default)
        val socket = try {
            aSocket(selector).udp().bind(InetSocketAddress("0.0.0.0", 0))
        } catch (e: Throwable) {
            selector.close()
            throw e
        }
        try {
            return withTimeout(timeoutMillis) {
                val sentAt = Clock.System.now()
                socket.send(
                    Datagram(
                        packet = ByteReadPacket(NtpPacket.request(sentAt)),
                        address = InetSocketAddress(server, port),
                    )
                )
                val response = socket.receive().packet.readBytes()
                NtpPacket.parseResponse(response, sentAt, Clock.System.now())
            }
        } finally {
            socket.close()
            selector.close()
        }
    }
}

/** RFC 5905/SNTP 48 字节报文的最小编解码。 */
object NtpPacket {
    private const val PACKET_SIZE = 48
    private const val UNIX_EPOCH_OFFSET_SECONDS = 2_208_988_800L

    fun request(transmitAt: Instant): ByteArray = ByteArray(PACKET_SIZE).also { packet ->
        packet[0] = 0x23 // LI=0, VN=4, Mode=3(client)
        writeTimestamp(packet, 40, transmitAt)
    }

    fun parseResponse(packet: ByteArray, sentAt: Instant, receivedAt: Instant): NtpSample {
        require(packet.size >= PACKET_SIZE) { "NTP response too short: ${packet.size}" }
        val leap = (packet[0].toInt() ushr 6) and 0x03
        val mode = packet[0].toInt() and 0x07
        val stratum = packet[1].toInt() and 0xff
        require(leap != 3) { "NTP server is unsynchronised" }
        require(mode == 4 || mode == 5) { "unexpected NTP mode: $mode" }
        require(stratum in 1..15) { "invalid NTP stratum: $stratum" }

        val t1 = readTimestamp(packet, 24)
        val t2 = readTimestamp(packet, 32)
        val t3 = readTimestamp(packet, 40)
        val sentMs = sentAt.toEpochMilliseconds()
        require(kotlin.math.abs(t1.toEpochMilliseconds() - sentMs) <= 1L) {
            "NTP originate timestamp mismatch"
        }
        val receivedMs = receivedAt.toEpochMilliseconds()
        val offset = ((t2.toEpochMilliseconds() - sentMs) +
            (t3.toEpochMilliseconds() - receivedMs)) / 2L
        val roundTrip = (receivedMs - sentMs) -
            (t3.toEpochMilliseconds() - t2.toEpochMilliseconds())
        return NtpSample(
            instant = Instant.fromEpochMilliseconds(receivedMs + offset),
            offsetMillis = offset,
            roundTripMillis = roundTrip.coerceAtLeast(0L),
        )
    }

    fun readTimestamp(packet: ByteArray, offset: Int): Instant {
        require(offset >= 0 && offset + 8 <= packet.size) { "timestamp outside packet" }
        val seconds = readUnsignedInt(packet, offset)
        val fraction = readUnsignedInt(packet, offset + 4)
        val unixMillis = (seconds - UNIX_EPOCH_OFFSET_SECONDS) * 1_000L +
            ((fraction * 1_000L + (1L shl 31)) ushr 32)
        return Instant.fromEpochMilliseconds(unixMillis)
    }

    fun writeTimestamp(packet: ByteArray, offset: Int, instant: Instant) {
        require(offset >= 0 && offset + 8 <= packet.size) { "timestamp outside packet" }
        val epochMillis = instant.toEpochMilliseconds()
        val seconds = epochMillis / 1_000L + UNIX_EPOCH_OFFSET_SECONDS
        val millis = epochMillis % 1_000L
        val fraction = (millis shl 32) / 1_000L
        writeUnsignedInt(packet, offset, seconds)
        writeUnsignedInt(packet, offset + 4, fraction)
    }

    private fun readUnsignedInt(packet: ByteArray, offset: Int): Long =
        ((packet[offset].toLong() and 0xffL) shl 24) or
            ((packet[offset + 1].toLong() and 0xffL) shl 16) or
            ((packet[offset + 2].toLong() and 0xffL) shl 8) or
            (packet[offset + 3].toLong() and 0xffL)

    private fun writeUnsignedInt(packet: ByteArray, offset: Int, value: Long) {
        packet[offset] = (value ushr 24).toByte()
        packet[offset + 1] = (value ushr 16).toByte()
        packet[offset + 2] = (value ushr 8).toByte()
        packet[offset + 3] = value.toByte()
    }
}

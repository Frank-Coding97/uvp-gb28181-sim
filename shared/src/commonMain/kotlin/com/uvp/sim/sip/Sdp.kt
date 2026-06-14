package com.uvp.sim.sip

/**
 * SDP (RFC 4566) parsing and building, scoped to GB/T 28181 video streams.
 *
 * GB28181 § 8 specifies a constrained SDP profile:
 *   v=0
 *   o=<deviceId> 0 0 IN IP4 <ip>
 *   s=Play | Playback | Download
 *   c=IN IP4 <ip>
 *   t=0 0
 *   m=video <port> RTP/AVP 96
 *   a=rtpmap:96 PS/90000
 *   a=sendrecv | recvonly | sendonly
 *   y=<ssrc>           ← GB28181 extension (10-digit decimal SSRC)
 *
 * The simulator only deals with the "Play" subset for M1 (real-time playback).
 * Recordings (Playback / Download) are M2.
 */
data class SdpOffer(
    /** Remote IP where RTP must be sent (from c= line). */
    val remoteIp: String,
    /** Remote RTP port (from m=video <port>). */
    val remotePort: Int,
    /** GB28181 SSRC string from y= line, may be null on 2016 minimal offers. */
    val ssrc: String?,
    /** Media direction the offerer wants. We answer with the inverse. */
    val direction: SdpDirection,
    /** Transport profile from m= line: RTP/AVP (UDP) or TCP/RTP/AVP (TCP RFC 4571). */
    val transport: SdpTransport = SdpTransport.UDP,
    /** TCP setup attribute from a=setup line. Only meaningful when [transport] = TCP. */
    val tcpSetup: SdpTcpSetup = SdpTcpSetup.PASSIVE,
    /** Whatever lines we don't understand — preserved for round-trip diagnostics. */
    val rawBody: String
)

enum class SdpDirection { SENDRECV, SENDONLY, RECVONLY, INACTIVE }

/** RTP transport from SDP m= line proto field. */
enum class SdpTransport { UDP, TCP }

/**
 * RFC 4145 setup attribute. For RTP/AVP over TCP per GB28181:
 *   - PASSIVE: device listens, platform connects (the common GB28181 default)
 *   - ACTIVE:  device connects to platform's listening port
 *   - ACTPASS: either side OK
 */
enum class SdpTcpSetup { ACTIVE, PASSIVE, ACTPASS }

object SdpParser {

    fun parseOffer(body: ByteArray): SdpOffer = parseOffer(body.decodeToString())

    fun parseOffer(text: String): SdpOffer {
        var ip: String? = null
        var port: Int? = null
        var ssrc: String? = null
        var direction = SdpDirection.SENDRECV
        var transport = SdpTransport.UDP
        var tcpSetup = SdpTcpSetup.PASSIVE

        for (line in text.lineSequence()) {
            val l = line.trim().trimEnd('\r')
            if (l.isEmpty()) continue
            when {
                l.startsWith("c=IN IP4 ") -> {
                    ip = l.removePrefix("c=IN IP4 ").trim()
                }
                l.startsWith("m=video ") -> {
                    // m=video <port> RTP/AVP 96  (UDP — default)
                    // m=video <port> TCP/RTP/AVP 96  (TCP — RFC 4571)
                    val parts = l.split(" ")
                    if (parts.size >= 4) {
                        port = parts[1].toIntOrNull()
                        transport = if (parts[2].startsWith("TCP", ignoreCase = true))
                            SdpTransport.TCP else SdpTransport.UDP
                    }
                }
                l == "a=sendrecv" -> direction = SdpDirection.SENDRECV
                l == "a=sendonly" -> direction = SdpDirection.SENDONLY
                l == "a=recvonly" -> direction = SdpDirection.RECVONLY
                l == "a=inactive" -> direction = SdpDirection.INACTIVE
                l == "a=setup:active" -> tcpSetup = SdpTcpSetup.ACTIVE
                l == "a=setup:passive" -> tcpSetup = SdpTcpSetup.PASSIVE
                l == "a=setup:actpass" -> tcpSetup = SdpTcpSetup.ACTPASS
                l.startsWith("y=") -> ssrc = l.removePrefix("y=").trim()
            }
        }

        require(ip != null) { "SDP offer missing c=IN IP4 ..." }
        require(port != null) { "SDP offer missing m=video <port>" }
        return SdpOffer(
            remoteIp = ip,
            remotePort = port,
            ssrc = ssrc,
            direction = direction,
            transport = transport,
            tcpSetup = tcpSetup,
            rawBody = text
        )
    }
}

object SdpAnswer {

    /**
     * GB28181 § C.2 媒体编码描述符 `f=`(非标但 EasyCVR / LiveGBS 等需要)。
     *
     * 完整格式: `f=v/<编码格式>/<分辨率>/<帧率>/<码率类型>/<码率大小>a/<编码格式>/<码率大小>/<采样率>`
     *
     * 视频编码: 1=MPEG-4 / 2=H.264 / 3=SVAC / 4=3GP / 5=H.265
     * 分辨率: 1=QCIF / 2=CIF / 3=4CIF / 4=D1 / 5=720p / 6=1080p
     * 码率类型: 1=CBR / 2=VBR
     * 音频编码: 1=G.711A / 2=G.711U / 3=G.722.1 / 4=G.723.1 / 5=G.729 / 6=G.726 / 11=AAC
     * 采样率: 1=8kHz / 2=14kHz / 3=16kHz / 4=32kHz
     *
     * 视频段必填,音频段任一字段缺失整段填 0。
     */
    data class MediaSpec(
        val videoCodec: Int,
        val resolution: Int,
        val frameRate: Int,
        val rateType: Int = 2,
        val videoBitrateKbps: Int,
        val audioCodec: Int? = null,
        val audioBitrateKbps: Int? = null,
        val audioSampleRate: Int? = null
    ) {
        fun toFLine(): String {
            val v = "v/$videoCodec/$resolution/$frameRate/$rateType/$videoBitrateKbps"
            val a = if (audioCodec != null && audioBitrateKbps != null && audioSampleRate != null) {
                "a/$audioCodec/$audioBitrateKbps/$audioSampleRate"
            } else "a///"
            return "f=$v$a"
        }
    }

    /**
     * Build a GB28181-compliant SDP answer for a "Play" INVITE.
     *
     * The answer is `sendonly` (we send, the platform receives), preserves the
     * offerer's `y=` SSRC verbatim (per GB28181 spec), and binds to our local
     * RTP port. The stream uses payload type 96 = PS/90000 per platform convention.
     *
     * @param ssrc 10-digit decimal SSRC string. Pass through from offer if present;
     *             if offer omitted y= (2016 minimal), pass a generated value.
     * @param mediaSpec when non-null, append the GB28181 § C.2 `f=` media descriptor
     *                  for EasyCVR / LiveGBS compatibility.
     */
    fun buildPlayAnswer(
        deviceId: String,
        localIp: String,
        localRtpPort: Int,
        ssrc: String,
        sessionName: String = "Play",
        transport: SdpTransport = SdpTransport.UDP,
        tcpSetup: SdpTcpSetup = SdpTcpSetup.PASSIVE,
        mediaSpec: MediaSpec? = null
    ): String {
        require(ssrc.length == 10) { "GB28181 SSRC must be 10 decimal digits, got '$ssrc'" }
        require(ssrc.all { it.isDigit() }) { "SSRC must be all digits: '$ssrc'" }
        return buildString {
            append("v=0\r\n")
            append("o=").append(deviceId).append(" 0 0 IN IP4 ").append(localIp).append("\r\n")
            append("s=").append(sessionName).append("\r\n")
            append("c=IN IP4 ").append(localIp).append("\r\n")
            append("t=0 0\r\n")
            val proto = if (transport == SdpTransport.TCP) "TCP/RTP/AVP" else "RTP/AVP"
            append("m=video ").append(localRtpPort).append(' ').append(proto).append(" 96\r\n")
            append("a=sendonly\r\n")
            append("a=rtpmap:96 PS/90000\r\n")
            if (transport == SdpTransport.TCP) {
                // Answer with the opposite of what platform offered:
                // platform offers PASSIVE (waits) → we ACTIVE (connect to it)
                // platform offers ACTIVE (will connect) → we PASSIVE (listen)
                val answerSetup = when (tcpSetup) {
                    SdpTcpSetup.PASSIVE -> "active"
                    SdpTcpSetup.ACTIVE -> "passive"
                    SdpTcpSetup.ACTPASS -> "active"
                }
                append("a=setup:").append(answerSetup).append("\r\n")
                append("a=connection:new\r\n")
            }
            append("y=").append(ssrc).append("\r\n")
            if (mediaSpec != null) {
                append(mediaSpec.toFLine()).append("\r\n")
            }
        }
    }
}

/** Helpers for converting between the GB28181 10-digit SSRC string and RTP's 32-bit int. */
object SsrcUtils {
    /**
     * Convert a 10-digit decimal SSRC string from a `y=` line to the 32-bit
     * unsigned int used in the RTP header.
     *
     * The string fits in a 32-bit unsigned (max 4294967295), so we use Long
     * intermediate to avoid signed overflow then narrow to Int.
     */
    fun toRtpInt(ssrc: String): Int {
        require(ssrc.length == 10) { "GB28181 SSRC must be 10 decimal digits" }
        return ssrc.toLong().toInt()
    }

    /**
     * Generate a 10-digit GB28181 SSRC string for cases where the offer didn't
     * include a `y=` line (2016 minimal). Format per GB28181 § C.2.4:
     *   first 1 digit:  0 = realtime, 1 = playback
     *   middle 5 digits: domain code (typically last 5 digits of SIP domain)
     *   last 4 digits:   sequence (we randomize)
     */
    fun generate(realtime: Boolean = true, domainCode: String, sequence: Int): String {
        require(domainCode.length == 5 && domainCode.all { it.isDigit() }) {
            "domainCode must be 5 digits"
        }
        val prefix = if (realtime) "0" else "1"
        val seqStr = (sequence and 0x0FFF).toString().padStart(4, '0').takeLast(4)
        return prefix + domainCode + seqStr
    }
}

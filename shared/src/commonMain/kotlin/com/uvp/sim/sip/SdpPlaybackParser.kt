package com.uvp.sim.sip

/**
 * GB/T 28181 §B.7 PLAYBACK INVITE 的 SDP offer。
 *
 * 与 [SdpParser.parseOffer] 的区别:
 *   - 识别 `s=Playback` / `s=Play`(`isPlayback` 字段区分)
 *   - 解 `t=<startEpochSec> <endEpochSec>`(实时推流是 `t=0 0`)
 *   - 解 `u=<channelId>:<ssrc>` 提取 channelId(国标扩展行)
 */
data class PlaybackOffer(
    val remoteIp: String,
    val remotePort: Int,
    val ssrc: String?,
    /** `u=` 行的 channelId 部分。无 u= 行返回 null。 */
    val channelId: String?,
    /** `t=` 起始 epoch 秒。Play 时是 0。 */
    val startEpochSec: Long,
    /** `t=` 结束 epoch 秒。Play 时是 0。 */
    val endEpochSec: Long,
    val isPlayback: Boolean
) {
    val startMs: Long get() = startEpochSec * 1000L
    val endMs: Long get() = endEpochSec * 1000L
}

object SdpPlaybackParser {

    fun parse(body: ByteArray): PlaybackOffer = parse(body.decodeToString())

    fun parse(text: String): PlaybackOffer {
        var ip: String? = null
        var port: Int? = null
        var ssrc: String? = null
        var channelId: String? = null
        var startSec = 0L
        var endSec = 0L
        var isPlayback = false

        for (line in text.lineSequence()) {
            val l = line.trim().trimEnd('\r')
            if (l.isEmpty()) continue
            when {
                l.startsWith("s=") -> {
                    val s = l.removePrefix("s=").trim()
                    isPlayback = s.equals("Playback", ignoreCase = true) ||
                        s.equals("Download", ignoreCase = true)
                }
                l.startsWith("c=IN IP4 ") -> {
                    ip = l.removePrefix("c=IN IP4 ").trim()
                }
                l.startsWith("m=video ") -> {
                    val parts = l.split(" ")
                    if (parts.size >= 4) port = parts[1].toIntOrNull()
                }
                l.startsWith("t=") -> {
                    val parts = l.removePrefix("t=").trim().split(" ", "\t").filter { it.isNotEmpty() }
                    if (parts.size >= 2) {
                        startSec = parts[0].toLongOrNull() ?: 0L
                        endSec = parts[1].toLongOrNull() ?: 0L
                    }
                }
                l.startsWith("u=") -> {
                    // 形如 "u=34020000001320000001:0" 或 "u=channelId"
                    val v = l.removePrefix("u=").trim()
                    val colon = v.indexOf(':')
                    channelId = if (colon > 0) v.substring(0, colon) else v
                    // u= 第二段(分号后)在 GB28181 是回放序号,不当 ssrc 用
                }
                l.startsWith("y=") -> ssrc = l.removePrefix("y=").trim()
            }
        }

        require(ip != null) { "SDP missing c=IN IP4 ..." }
        require(port != null) { "SDP missing m=video <port>" }
        return PlaybackOffer(
            remoteIp = ip,
            remotePort = port,
            ssrc = ssrc,
            channelId = channelId,
            startEpochSec = startSec,
            endEpochSec = endSec,
            isPlayback = isPlayback
        )
    }
}

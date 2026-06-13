package com.uvp.sim.sip

/**
 * MANSRTSP 控制命令(GB/T 28181-2022 §9.7.2 PLAY/PAUSE/Range/Scale)。
 *
 * INFO 报文 body 结构示例:
 * ```
 * PLAY rtsp://192.168.1.10:8000/replay/3402... RTSP/1.0
 * CSeq: 1
 * Range: npt=120.0-
 * Scale: 2.0
 * ```
 *
 * 设备只解析,不需要构造 MANSRTSP 应答(SIP INFO 只回 200 OK,无 body)。
 */
sealed class MansRtspCommand {
    abstract val cseq: Int

    /**
     * @param rangeStartMs Range 字段 npt 起点(ms)。`null` = 没带 Range 或 `npt=now-`(从暂停点继续)
     * @param scale Scale 字段。`null` = 没带 Scale(保持当前倍速)。合规过滤(0.25/0.5/1/2/4) 在 handler 处。
     */
    data class Play(
        override val cseq: Int,
        val rangeStartMs: Long? = null,
        val scale: Double? = null
    ) : MansRtspCommand()

    data class Pause(
        override val cseq: Int
    ) : MansRtspCommand()

    data class Teardown(
        override val cseq: Int
    ) : MansRtspCommand()
}

class MansRtspParseException(message: String) : RuntimeException(message)

/**
 * 手写 RTSP-style parser(plan §4.5):
 * - CRLF / LF 兼容
 * - header 大小写不敏感
 * - 多余空格容忍
 * - header 顺序无关
 *
 * 不依赖 JVM-only API,放 commonMain。
 */
object MansRtspParser {

    fun parse(body: String): MansRtspCommand {
        val normalized = body.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) {
            throw MansRtspParseException("empty body")
        }
        val lines = normalized.split('\n')
        val requestLine = lines.firstOrNull()?.trim()
            ?: throw MansRtspParseException("missing request line")
        val method = requestLine.split(' ').firstOrNull()
            ?.uppercase()
            ?: throw MansRtspParseException("missing method")

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }

        val cseq = headers["cseq"]?.toIntOrNull()
            ?: throw MansRtspParseException("missing or invalid CSeq")

        return when (method) {
            "PLAY" -> {
                val rangeStartMs = parseRangeStart(headers["range"])
                val scale = headers["scale"]?.toDoubleOrNull()
                MansRtspCommand.Play(cseq = cseq, rangeStartMs = rangeStartMs, scale = scale)
            }
            "PAUSE" -> MansRtspCommand.Pause(cseq = cseq)
            "TEARDOWN" -> MansRtspCommand.Teardown(cseq = cseq)
            else -> throw MansRtspParseException("unsupported method: $method")
        }
    }

    /**
     * Range 字段格式(RFC 2326 / GB/T §B.7):
     * - `npt=120.0-` 单端,从 120s 到末尾 → 120_000
     * - `npt=120.0-180.0` 区间,只取起点 → 120_000
     * - `npt=now-` 继续 → null
     * - `npt=now-end` 同上 → null
     * - 无 Range 字段 → null
     */
    private fun parseRangeStart(value: String?): Long? {
        if (value == null) return null
        val v = value.trim()
        if (!v.startsWith("npt=", ignoreCase = true)) return null
        val payload = v.substring(4)
        val dashIdx = payload.indexOf('-')
        val startToken = if (dashIdx >= 0) payload.substring(0, dashIdx).trim() else payload.trim()
        if (startToken.isEmpty() || startToken.equals("now", ignoreCase = true)) return null
        val seconds = startToken.toDoubleOrNull() ?: return null
        return (seconds * 1000.0).toLong()
    }
}

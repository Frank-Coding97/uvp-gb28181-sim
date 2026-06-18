package com.uvp.sim.sip

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * 解析 SIP `Date` 头(RFC 3261 § 20.17),返回 [Instant] 或 null。
 *
 * 双格式兼容:
 * - **RFC1123**(主流):`Wed, 18 Jun 2026 07:30:00 GMT` —— WVP / 自家 SipBuilders 都用这个
 * - **ISO8601**:
 *   - 带 Z / +08:00 等显式时区:按 [Instant.parse] 直解
 *   - **无时区**:按**系统默认时区**解析(GB28181 业界惯例,WVP 实测发本地时间无后缀)
 *
 * 任何异常 / 格式不识别返回 null,不抛 —— 调用方按"未校时"降级。
 */
object SipDateParser {

    fun parse(raw: String?): Instant? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return parseRfc1123(s) ?: parseIso8601(s)
    }

    /**
     * RFC1123: `Wed, 18 Jun 2026 07:30:00 GMT`
     * 6 段 split:`Wed,` `18` `Jun` `2026` `07:30:00` `GMT`
     */
    private fun parseRfc1123(s: String): Instant? = runCatching {
        val parts = s.split(' ').filter { it.isNotBlank() }
        if (parts.size < 5) return null
        val day = parts[1].toInt()
        val mon = monthOrNull(parts[2]) ?: return null
        val year = parts[3].toInt()
        val time = parts[4].split(':')
        if (time.size != 3) return null
        val h = time[0].toInt()
        val m = time[1].toInt()
        val sec = time[2].toInt()
        val isoLocal = buildString {
            append(year.toString().padStart(4, '0'))
            append('-').append(mon.toString().padStart(2, '0'))
            append('-').append(day.toString().padStart(2, '0'))
            append('T')
            append(h.toString().padStart(2, '0'))
            append(':').append(m.toString().padStart(2, '0'))
            append(':').append(sec.toString().padStart(2, '0'))
            append('Z')
        }
        Instant.parse(isoLocal)
    }.getOrNull()

    /**
     * ISO8601:
     * 1. 含显式时区(Z / ±HH:MM)→ Instant.parse 直解
     * 2. 无时区(纯 LocalDateTime)→ 按**系统默认时区**解析
     *    (WVP-Pro 实测会发 `2026-06-18T16:26:57.492` 这种本地时间无后缀,
     *     如果当 UTC 解析会差一个时区偏移)
     *
     * 必须含 `T` 分隔符避免 "2026-06-18" 这种半截日期被当成合法。
     */
    private fun parseIso8601(s: String): Instant? {
        if (!s.contains('T')) return null
        // 优先按显式时区直解(Z / +08:00 / -05:00 等)
        runCatching { return Instant.parse(s) }
        // 无时区 → LocalDateTime + 系统默认时区
        runCatching { return LocalDateTime.parse(s).toInstant(TimeZone.currentSystemDefault()) }
        return null
    }

    private fun monthOrNull(name: String): Int? = when (name.lowercase()) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4
        "may" -> 5; "jun" -> 6; "jul" -> 7; "aug" -> 8
        "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
        else -> null
    }
}

package com.uvp.sim.gb28181

import com.uvp.sim.config.GeoPoint
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object MobilePositionNotify {

    /**
     * 构造 MobilePosition NOTIFY XML body。
     *
     * ⚠️ 单位契约(plan §6.2 采纳)—— builder 承担协议层单位换算职责:
     *   · [speed] **输入** m/s(与 [com.uvp.sim.domain.location.PositionFix.speed] 一致),
     *     **输出** km/h(内部 × 3.6,符合 GB/T 28181 §9.3.5.2 Speed 单位定义)
     *   · [direction] 度(0-360),透传
     *   · [altitude] 米,透传
     *   · [fixTimeMs] epoch ms,**秒截断**后转东八区 `Asia/Shanghai` 输出 `YYYY-MM-DDTHH:mm:ss`;
     *     传 0L 时 fallback 到 [nowTimestamp](系统当前时间)—— 兼容旧调用与 mock 场景
     */
    fun build(
        deviceId: String,
        sn: Int,
        point: GeoPoint,
        speed: Double,
        direction: Double,
        altitude: Double = 0.0,
        fixTimeMs: Long = 0L,
    ): String {
        val lngStr = formatDouble(point.longitude, 6)
        val latStr = formatDouble(point.latitude, 6)
        val spdStr = formatDouble(speed * 3.6, 1) // m/s → km/h,GB/T 28181 §9.3.5.2
        val dirStr = formatDouble(direction, 1)
        val altStr = formatDouble(altitude, 1)
        val timestamp = if (fixTimeMs > 0L) formatFixTime(fixTimeMs) else nowTimestamp()
        return """<?xml version="1.0" encoding="UTF-8"?>
<Notify>
<CmdType>MobilePosition</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<Time>$timestamp</Time>
<Longitude>$lngStr</Longitude>
<Latitude>$latStr</Latitude>
<Speed>$spdStr</Speed>
<Direction>$dirStr</Direction>
<Altitude>$altStr</Altitude>
</Notify>
""".replace("\n", "\r\n")
    }

    private fun nowTimestamp(): String {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        val ldt = now.toLocalDateTime(tz)
        return "${ldt.year}-${ldt.monthNumber.toString().padStart(2, '0')}-" +
            "${ldt.dayOfMonth.toString().padStart(2, '0')}T" +
            "${ldt.hour.toString().padStart(2, '0')}:" +
            "${ldt.minute.toString().padStart(2, '0')}:" +
            ldt.second.toString().padStart(2, '0')
    }

    /**
     * fix 采集时间格式化 — 秒截断(不四舍五入) + 东八区 Asia/Shanghai。
     * plan §6.1 Codex R1 P2 采纳:平台按东八区解析 <Time>,毫秒丢弃避免格式歧义。
     */
    private fun formatFixTime(fixTimeMs: Long): String {
        val truncatedSec = (fixTimeMs / 1000L) * 1000L
        val ldt = Instant.fromEpochMilliseconds(truncatedSec)
            .toLocalDateTime(TimeZone.of("Asia/Shanghai"))
        return "${ldt.year}-${ldt.monthNumber.toString().padStart(2, '0')}-" +
            "${ldt.dayOfMonth.toString().padStart(2, '0')}T" +
            "${ldt.hour.toString().padStart(2, '0')}:" +
            "${ldt.minute.toString().padStart(2, '0')}:" +
            ldt.second.toString().padStart(2, '0')
    }

    /**
     * KMP 友好的小数格式化(commonMain 没有 String.format / printf,jvm-only)。
     * 对 [decimals] 位小数 half-up 四舍五入(避开 [kotlin.math.round] 的 banker rounding)。
     *
     * 跟 [MobilePositionResponse.formatDouble] 行为一致 — 两处共用同款语义,
     * 一并维持 byte-equivalent 输出格式("%.Nf" 半进位 + 定长小数位)。
     */
    private fun formatDouble(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toLong().toString()
        var multiplier = 1L
        repeat(decimals) { multiplier *= 10 }
        val scaled = value * multiplier
        val rounded = if (scaled >= 0) {
            kotlin.math.floor(scaled + 0.5).toLong()
        } else {
            -kotlin.math.floor(-scaled + 0.5).toLong()
        }
        val sign = if (rounded < 0) "-" else ""
        val abs = kotlin.math.abs(rounded)
        val intPart = abs / multiplier
        val fracPart = abs % multiplier
        val fracStr = fracPart.toString().padStart(decimals, '0')
        return "$sign$intPart.$fracStr"
    }
}

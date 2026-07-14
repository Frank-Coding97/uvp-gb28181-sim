package com.uvp.sim.gb28181

import com.uvp.sim.config.GeoPoint
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * GB/T 28181 §9.5.4 MobilePosition 单次查询应答构造。
 *
 * 单位契约与 [MobilePositionNotify.build] 完全一致(见其 KDoc):
 *   · speed 输入 m/s,输出 km/h(×3.6)
 *   · fixTimeMs > 0 走秒截断东八区格式化,== 0 走 nowTimestamp fallback
 */
object MobilePositionResponse {

    fun build(
        deviceId: String,
        sn: String,
        point: GeoPoint,
        speed: Double,
        direction: Double,
        altitude: Double,
        timestamp: String? = null,
        fixTimeMs: Long = 0L,
    ): String {
        val lngStr = formatDouble(point.longitude, 6)
        val latStr = formatDouble(point.latitude, 6)
        val spdStr = formatDouble(speed * 3.6, 1) // m/s → km/h
        val dirStr = formatDouble(direction, 1)
        val altStr = formatDouble(altitude, 1)
        val timeStr = when {
            timestamp != null -> timestamp
            fixTimeMs > 0L -> formatFixTime(fixTimeMs)
            else -> nowTimestamp()
        }
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>MobilePosition</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<Result>OK</Result>
<Time>$timeStr</Time>
<Longitude>$lngStr</Longitude>
<Latitude>$latStr</Latitude>
<Speed>$spdStr</Speed>
<Direction>$dirStr</Direction>
<Altitude>$altStr</Altitude>
</Response>
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
     */
    private fun formatDouble(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toLong().toString()
        var multiplier = 1L
        repeat(decimals) { multiplier *= 10 }
        // half-up:加 0.5 再下取整,负数对称处理
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

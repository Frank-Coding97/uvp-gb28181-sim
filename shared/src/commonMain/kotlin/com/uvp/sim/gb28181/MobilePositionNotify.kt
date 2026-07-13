package com.uvp.sim.gb28181

import com.uvp.sim.config.GeoPoint
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object MobilePositionNotify {

    fun build(
        deviceId: String,
        sn: Int,
        point: GeoPoint,
        speed: Double,
        direction: Double,
        altitude: Double = 0.0,
        timestamp: String = nowTimestamp()
    ): String {
        val lngStr = formatDouble(point.longitude, 6)
        val latStr = formatDouble(point.latitude, 6)
        val spdStr = formatDouble(speed, 1)
        val dirStr = formatDouble(direction, 1)
        val altStr = formatDouble(altitude, 1)
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

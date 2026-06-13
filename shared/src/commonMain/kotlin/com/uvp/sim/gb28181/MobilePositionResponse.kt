package com.uvp.sim.gb28181

import com.uvp.sim.config.GeoPoint

/**
 * GB/T 28181 §9.5.4 MobilePosition 单次查询应答构造。
 *
 * 平台下发 MESSAGE body:
 * ```xml
 * <Query>
 *   <CmdType>MobilePosition</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 * </Query>
 * ```
 *
 * 设备回 MESSAGE body:
 * ```xml
 * <Response>
 *   <CmdType>MobilePosition</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <Result>OK</Result>
 *   <Time>2026-06-13T18:00:00</Time>
 *   <Longitude>116.404</Longitude>
 *   <Latitude>39.915</Latitude>
 *   <Speed>0.0</Speed>
 *   <Direction>0.0</Direction>
 *   <Altitude>0.0</Altitude>
 * </Response>
 * ```
 *
 * 区别于 [MobilePositionNotify](周期推送用 <Notify> wrapper):
 *   - 单次查询用 <Response>,带 <Result>OK</Result>
 *   - 字段语义一致,值由 SimulatorEngine 从 MockGpsSource 取最新一条 fix
 */
object MobilePositionResponse {

    fun build(
        deviceId: String,
        sn: String,
        point: GeoPoint,
        speed: Double,
        direction: Double,
        altitude: Double,
        timestamp: String
    ): String {
        val lngStr = formatDouble(point.longitude, 6)
        val latStr = formatDouble(point.latitude, 6)
        val spdStr = formatDouble(speed, 1)
        val dirStr = formatDouble(direction, 1)
        val altStr = formatDouble(altitude, 1)
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>MobilePosition</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<Result>OK</Result>
<Time>$timestamp</Time>
<Longitude>$lngStr</Longitude>
<Latitude>$latStr</Latitude>
<Speed>$spdStr</Speed>
<Direction>$dirStr</Direction>
<Altitude>$altStr</Altitude>
</Response>
""".replace("\n", "\r\n")
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

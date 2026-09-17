package com.uvp.sim.gb28181

import com.uvp.sim.config.GeoPoint
import com.uvp.sim.domain.ProtocolClock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * ⚠️ **私有扩展,不是 GB/T 28181 的标准形态** —— MobilePosition 单次查询应答(`<Response>`)构造。
 *
 * 2026-09-17 条款号更正:原 KDoc 写「§9.5.4」是**虚号**。已 OCR 两版原文核过:
 *   · GB/T 28181-2022 与 2016 的 §9.5(网络设备信息查询)**都只有 9.5.1 / 9.5.2 / 9.5.3**,
 *     **没有 §9.5.4**;两版 §9.5.1 的查询命令枚举均为「设备目录 / 前端设备信息 / 前端设备状态信息 /
 *     设备配置 / 预置位」——**都没有移动位置**;
 *   · 附录 A 里 **A.2.6(应答命令)没有 MobilePosition 条目** → 标准里不存在「查询/应答」这一对。
 *
 * 标准里移动位置**只有一条路**:SUBSCRIBE(消息体 A.2.4.9,见 §9.11.1.3 c))
 * + NOTIFY(消息体 A.2.5.6,见 §9.11.2.3 c))。
 *
 * 本对象保留纯为 **WVP 等既有平台的私有兼容口径**,**勿当对标能力引用**。
 * 位置能力**对标标准的那一半**在 [MobilePositionNotify] —— 2026-09-17 已按主仓 `F-10` 补上
 * 2022 列表形态并保留 2016 扁平形态(双版本并存,见其 KDoc)。
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
        val now = ProtocolClock.now()
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

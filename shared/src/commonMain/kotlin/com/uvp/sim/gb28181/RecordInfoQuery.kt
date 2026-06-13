package com.uvp.sim.gb28181

import com.uvp.sim.recording.RecordType
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * GB/T 28181 §9.4 RecordInfo 查询解析。
 *
 * 平台下发(MESSAGE body):
 * ```xml
 * <Query>
 *   <CmdType>RecordInfo</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>     ← 通道 ID(查哪个通道)
 *   <StartTime>2026-06-01T00:00:00</StartTime>
 *   <EndTime>2026-06-12T23:59:59</EndTime>
 *   <Type>time|alarm|manual|all</Type>  ← 可选
 *   <Secrecy>0</Secrecy>                ← 可选
 * </Query>
 * ```
 *
 * 时间是本地时间无偏移,需要传入 [TimeZone] 解码成 epoch ms。
 */
data class RecordInfoQueryRequest(
    val sn: String,
    val channelId: String,
    val startMs: Long,
    val endMs: Long,
    val type: RecordType?,
    val secrecy: Int
)

object RecordInfoQuery {

    fun parse(xml: String, timeZoneId: String): RecordInfoQueryRequest? {
        val sn = ManscdpParser.sn(xml) ?: return null
        val channel = ManscdpParser.deviceId(xml) ?: return null
        val startStr = ManscdpParser.tagValue(xml, "StartTime") ?: return null
        val endStr = ManscdpParser.tagValue(xml, "EndTime") ?: return null
        val tz = runCatching { TimeZone.of(timeZoneId) }.getOrDefault(TimeZone.UTC)
        val startMs = parseLocalIso(startStr, tz) ?: return null
        val endMs = parseLocalIso(endStr, tz) ?: return null
        val typeStr = ManscdpParser.tagValue(xml, "Type")
        val type = when (typeStr?.lowercase()) {
            null, "all" -> null
            "time" -> RecordType.Time
            "alarm" -> RecordType.Alarm
            "manual" -> RecordType.Manual_
            else -> null
        }
        val secrecy = ManscdpParser.tagValue(xml, "Secrecy")?.toIntOrNull() ?: 0
        return RecordInfoQueryRequest(
            sn = sn,
            channelId = channel,
            startMs = startMs,
            endMs = endMs,
            type = type,
            secrecy = secrecy
        )
    }

    private fun parseLocalIso(s: String, tz: TimeZone): Long? = runCatching {
        LocalDateTime.parse(s).toInstant(tz).toEpochMilliseconds()
    }.getOrNull()
}

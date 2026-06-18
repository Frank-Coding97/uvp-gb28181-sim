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
 *   <!-- M5 batch2 §3.11 高级过滤(自建平台 / EasyGBS 扩展) -->
 *   <IndistinctQuery>1</IndistinctQuery> ← 可选,1=模糊查询子设备
 *   <FilePath>...</FilePath>             ← 可选,录像文件路径
 *   <Address>...</Address>               ← 可选,录像地址
 *   <RecorderID>...</RecorderID>         ← 可选,录像器 ID
 * </Query>
 * ```
 *
 * 时间是本地时间无偏移,需要传入 [TimeZone] 解码成 epoch ms。
 *
 * **过滤语义**(plan §Q3):
 * - 4 个高级字段当前**仅解析透传**,不参与 mock 录像命中集过滤
 * - IndistinctQuery=1 留 M6 多通道 / 子目录录像时启用真实生效
 */
data class RecordInfoQueryRequest(
    val sn: String,
    val channelId: String,
    val startMs: Long,
    val endMs: Long,
    val type: RecordType?,
    val secrecy: Int,
    val indistinctQuery: Int = 0,
    val filePath: String? = null,
    val address: String? = null,
    val recorderId: String? = null
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
        val indistinctQuery = ManscdpParser.tagValue(xml, "IndistinctQuery")?.toIntOrNull() ?: 0
        val filePath = ManscdpParser.tagValue(xml, "FilePath")?.takeIf { it.isNotBlank() }
        val address = ManscdpParser.tagValue(xml, "Address")?.takeIf { it.isNotBlank() }
        val recorderId = ManscdpParser.tagValue(xml, "RecorderID")?.takeIf { it.isNotBlank() }
        return RecordInfoQueryRequest(
            sn = sn,
            channelId = channel,
            startMs = startMs,
            endMs = endMs,
            type = type,
            secrecy = secrecy,
            indistinctQuery = indistinctQuery,
            filePath = filePath,
            address = address,
            recorderId = recorderId
        )
    }

    private fun parseLocalIso(s: String, tz: TimeZone): Long? = runCatching {
        LocalDateTime.parse(s).toInstant(tz).toEpochMilliseconds()
    }.getOrNull()
}

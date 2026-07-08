package com.uvp.sim.gb28181

import com.uvp.sim.recording.RecordingFile
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * GB/T 28181 §9.4 RecordInfo 应答构造。
 *
 * 录像清单可能数百条,平台不能在一个 SIP MESSAGE 里塞那么大的 XML。
 * 所以分页,每包 [DEFAULT_PAGE_SIZE](50) 条。每包的 SumNum 一致(全量总数),
 * RecordList Num 是当前包条数。
 *
 * 时间格式:本地时间无时区偏移,如 `2026-06-12T21:30:15`(国标默认)。
 */
object RecordInfoNotify {

    const val DEFAULT_PAGE_SIZE = 50

    /** 单包构造。一般通过 [buildAll] 调用。 */
    fun buildPacket(
        sn: String,
        deviceId: String,
        deviceName: String,
        sumNum: Int,
        items: List<RecordingFile>,
        timeZoneId: String = "Asia/Shanghai"
    ): String {
        val tz = runCatching { TimeZone.of(timeZoneId) }.getOrDefault(TimeZone.UTC)
        val itemsXml = items.joinToString("\n") { f ->
            buildItem(f, deviceId = deviceId, tz = tz)
        }
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>RecordInfo</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<Name>$deviceName</Name>
<SumNum>$sumNum</SumNum>
<RecordList Num="${items.size}">
$itemsXml
</RecordList>
</Response>
""".replace("\n", "\r\n")
    }

    /**
     * 全量分页构造。返回的多包共享同一 SumNum,每包 RecordList Num 是当前页条数。
     * 空清单返回单包(SumNum=0, RecordList Num=0)。
     */
    fun buildAll(
        sn: String,
        deviceId: String,
        deviceName: String,
        items: List<RecordingFile>,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        timeZoneId: String = "Asia/Shanghai"
    ): List<String> {
        if (items.isEmpty()) {
            return listOf(
                buildPacket(
                    sn = sn,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    sumNum = 0,
                    items = emptyList(),
                    timeZoneId = timeZoneId
                )
            )
        }
        val sumNum = items.size
        return items.chunked(pageSize).map { chunk ->
            buildPacket(
                sn = sn,
                deviceId = deviceId,
                deviceName = deviceName,
                sumNum = sumNum,
                items = chunk,
                timeZoneId = timeZoneId
            )
        }
    }

    private fun buildItem(f: RecordingFile, deviceId: String, tz: TimeZone): String {
        val startStr = formatLocalIso(f.startTimeMs, tz)
        val endStr = formatLocalIso(f.endTimeMs, tz)
        return """<Item>
<DeviceID>${f.channelId}</DeviceID>
<Name>录像-$startStr</Name>
<FilePath>${f.filePath}</FilePath>
<Address>Local</Address>
<StartTime>$startStr</StartTime>
<EndTime>$endStr</EndTime>
<Secrecy>${f.secrecy}</Secrecy>
<Type>${f.type.gb28181Token}</Type>
<RecorderID>$deviceId</RecorderID>
</Item>"""
    }

    private fun formatLocalIso(epochMs: Long, tz: TimeZone): String {
        val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
        return buildString {
            append(ldt.year.toString().padStart(4, '0'))
            append('-')
            append(ldt.monthNumber.toString().padStart(2, '0'))
            append('-')
            append(ldt.dayOfMonth.toString().padStart(2, '0'))
            append('T')
            append(ldt.hour.toString().padStart(2, '0'))
            append(':')
            append(ldt.minute.toString().padStart(2, '0'))
            append(':')
            append(ldt.second.toString().padStart(2, '0'))
        }
    }
}

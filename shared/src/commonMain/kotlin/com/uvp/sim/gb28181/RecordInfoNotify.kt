package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
import com.uvp.sim.recording.RecordingFile
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * GB/T 28181 §9.4 RecordInfo 应答构造（A.2.6.7，item 类型见 A.2.1.10）。
 *
 * 录像清单可能数百条,平台不能在一个 SIP MESSAGE 里塞那么大的 XML。
 * 所以分页,每包 [DEFAULT_PAGE_SIZE](50) 条。每包的 SumNum 一致(全量总数),
 * RecordList Num 是当前包条数。
 *
 * 时间格式:本地时间无时区偏移,如 `2026-06-12T21:30:15`(国标默认)。
 *
 * ⭐ **2026-09-19 补齐 3 个字段**（此前 `Item` 只发到 `RecorderID`）:
 *
 * | 字段 | 版本 | 说明 |
 * |---|---|---|
 * | `FileSize` | **两版都有**（2016 A.2.1.10 末尾、2022 同位置） | 录像文件大小，**单位 Byte** |
 * | `RecordLocation` | 2022 新增 | 存储该录像的设备/系统编码（标准注明"模糊查询时必选"） |
 * | `StreamNumber` | 2022 新增 | 该项录像属哪路码流 |
 *
 * ⇒ 2016 分支发到 `FileSize` 为止；2022 分支再追加后两个。
 * 用 [GbVersion] 分支而**不是**一律发全 —— 多发的元素在严格校验的对端上会让整条报文非法，
 * 且 2016 的对端根本没有这两个概念。
 *
 * ⛔ `gbVersion` 刻意**不给默认值**：给默认值会把"忘传 → 悄悄按某一版发"变成一次
 * 静默的错误形态应答，而这恰好只在联调（对方版本与本机声明不同）时才出现。
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
        gbVersion: GbVersion,
        timeZoneId: String = "Asia/Shanghai",
    ): String {
        val tz = runCatching { TimeZone.of(timeZoneId) }.getOrDefault(TimeZone.UTC)
        val itemsXml = items.joinToString("\n") { f ->
            buildItem(f, deviceId = deviceId, tz = tz, gbVersion = gbVersion)
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
        gbVersion: GbVersion,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        timeZoneId: String = "Asia/Shanghai",
    ): List<String> {
        if (items.isEmpty()) {
            return listOf(
                buildPacket(
                    sn = sn,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    sumNum = 0,
                    items = emptyList(),
                    gbVersion = gbVersion,
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
                gbVersion = gbVersion,
                timeZoneId = timeZoneId
            )
        }
    }

    private fun buildItem(
        f: RecordingFile,
        deviceId: String,
        tz: TimeZone,
        gbVersion: GbVersion,
    ): String {
        val startStr = formatLocalIso(f.startTimeMs, tz)
        val endStr = formatLocalIso(f.endTimeMs, tz)
        return buildString {
            append("<Item>\n")
            append("<DeviceID>").append(f.channelId).append("</DeviceID>\n")
            append("<Name>录像-").append(startStr).append("</Name>\n")
            append("<FilePath>").append(f.filePath).append("</FilePath>\n")
            append("<Address>Local</Address>\n")
            append("<StartTime>").append(startStr).append("</StartTime>\n")
            append("<EndTime>").append(endStr).append("</EndTime>\n")
            append("<Secrecy>").append(f.secrecy).append("</Secrecy>\n")
            append("<Type>").append(f.type.gb28181Token).append("</Type>\n")
            append("<RecorderID>").append(deviceId).append("</RecorderID>\n")
            // FileSize 两版都在 itemFileType 里（2016 也有），单位 Byte —— 直接取索引里的真实字节数。
            append("<FileSize>").append(f.sizeBytes).append("</FileSize>\n")
            if (gbVersion >= GbVersion.V2022) {
                // RecordLocation = 存储这份录像的设备/系统编码。本机录在本机，就是设备自己。
                append("<RecordLocation>").append(deviceId).append("</RecordLocation>\n")
                append("<StreamNumber>").append(f.streamNumber).append("</StreamNumber>\n")
            }
            append("</Item>")
        }
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

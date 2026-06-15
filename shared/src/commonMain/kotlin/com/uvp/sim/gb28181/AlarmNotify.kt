package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * GB/T 28181-2022 §9.5.1 Alarm Notify body builder。
 *
 * [buildAlarm] 是主入口,接受完整 [AlarmPayload](9 字段)。
 * [buildSnapshotAlarm] 是 M1 抓拍借壳路径,内部委托 [buildAlarm](默认
 * priority=4 / method=5 / type=5),snapshot 上报报文随之升级为 GB-2022 全集。
 */
object AlarmNotify {

    /**
     * 构造完整的 §9.5.1 报警通知 body。
     *
     * 字段顺序严格对齐国标:
     *   SN / DeviceID / AlarmPriority / AlarmMethod / AlarmTime /
     *   AlarmDescription / Longitude / Latitude / Info(AlarmType[+AlarmTypeParam])
     *
     * - 经纬度为 null 时输出空 element(WVP 容忍)
     * - AlarmType / AlarmTypeParam 是 §A.2.5 扩展,放 <Info> 容器
     * - XML 编码 GB2312(§10.1 全局合规),行结尾 \r\n
     */
    fun buildAlarm(config: SimConfig, sn: String, payload: AlarmPayload): String {
        val timestamp = if (payload.timeMs > 0L) formatTimestamp(payload.timeMs) else nowTimestamp()
        val lng = payload.longitude?.toString() ?: ""
        val lat = payload.latitude?.toString() ?: ""
        val typeParamLine = payload.typeParam
            ?.let { "\n<AlarmTypeParam>${escape(it)}</AlarmTypeParam>" }
            ?: ""
        return """<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Alarm</CmdType>
<SN>$sn</SN>
<DeviceID>${payload.deviceId}</DeviceID>
<AlarmPriority>${payload.priority.code}</AlarmPriority>
<AlarmMethod>${payload.method.code}</AlarmMethod>
<AlarmTime>$timestamp</AlarmTime>
<AlarmDescription>${escape(payload.description)}</AlarmDescription>
<Longitude>$lng</Longitude>
<Latitude>$lat</Latitude>
<Info>
<AlarmType>${payload.type.code}</AlarmType>$typeParamLine
</Info>
</Notify>
""".replace("\n", "\r\n")
    }

    /**
     * M1 抓拍上报借壳路径。委托 [buildAlarm],字段值维持 snapshot 语义:
     * priority=General(4) / method=Video(5) / type=Other(5)。
     */
    fun buildSnapshotAlarm(
        config: SimConfig,
        sn: String,
        description: String = "Snapshot uploaded"
    ): String = buildAlarm(
        config = config,
        sn = sn,
        payload = AlarmPayload(
            deviceId = config.device.alarmChannelId,
            priority = AlarmPriority.General,
            method = AlarmMethod.Video,
            type = AlarmType.Other,
            description = description
        )
    )

    /** XML 文本节点转义,覆盖 5 个保留字符。 */
    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /** YYYY-MM-DDTHH:MM:SS in local time, zero-padded. */
    private fun nowTimestamp(): String = formatTimestamp(Clock.System.now().toEpochMilliseconds())

    private fun formatTimestamp(epochMs: Long): String {
        val tz = TimeZone.currentSystemDefault()
        val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
        return "${ldt.year}-${ldt.monthNumber.toString().padStart(2, '0')}-" +
            "${ldt.dayOfMonth.toString().padStart(2, '0')}T" +
            "${ldt.hour.toString().padStart(2, '0')}:" +
            "${ldt.minute.toString().padStart(2, '0')}:" +
            ldt.second.toString().padStart(2, '0')
    }
}

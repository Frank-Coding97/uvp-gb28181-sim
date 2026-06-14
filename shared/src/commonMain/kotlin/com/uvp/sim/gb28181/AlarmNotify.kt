package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * GB28181 § 9.5 Alarm Notify body builder.
 *
 * Used by SimulatorEngine.reportSnapshot() to ask the platform to fetch a
 * snapshot. M1 only sends the SIP Notify — the HTTP image upload pipeline
 * (e.g. /api/snapshot 接口接收 base64) is M2 since it requires the
 * device-snapshot 命令的双向流程.
 */
object AlarmNotify {

    /**
     * Build a generic Alarm Notify body for a "snapshot" event.
     *
     * Per GB28181-2022 §9.5 the body is:
     *   <Notify>
     *     <CmdType>Alarm</CmdType>
     *     <SN>...</SN>
     *     <DeviceID>设备ID</DeviceID>
     *     <AlarmPriority>4</AlarmPriority>
     *     <AlarmMethod>5</AlarmMethod>   <!-- video alarm -->
     *     <AlarmTime>YYYY-MM-DDTHH:MM:SS</AlarmTime>
     *     <AlarmDescription>...</AlarmDescription>
     *   </Notify>
     */
    fun buildSnapshotAlarm(
        config: SimConfig,
        sn: String,
        description: String = "Snapshot uploaded"
    ): String {
        val device = config.device
        // Compose YYYY-MM-DDTHH:MM:SS without bringing in kotlinx-datetime.
        val timestamp = nowTimestamp()
        return """<?xml version="1.0" encoding="UTF-8"?>
<Notify>
<CmdType>Alarm</CmdType>
<SN>$sn</SN>
<DeviceID>${device.alarmChannelId}</DeviceID>
<AlarmPriority>4</AlarmPriority>
<AlarmMethod>5</AlarmMethod>
<AlarmTime>$timestamp</AlarmTime>
<AlarmDescription>$description</AlarmDescription>
</Notify>
""".replace("\n", "\r\n")
    }

    /** YYYY-MM-DDTHH:MM:SS in local time, zero-padded. Best-effort cross-platform. */
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
}

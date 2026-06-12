package com.uvp.sim.gb28181

import com.uvp.sim.config.GeoPoint
import kotlinx.datetime.Clock
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
        val lngStr = "%.6f".format(point.longitude)
        val latStr = "%.6f".format(point.latitude)
        val spdStr = "%.1f".format(speed)
        val dirStr = "%.1f".format(direction)
        val altStr = "%.1f".format(altitude)
        return """<?xml version="1.0" encoding="GB2312"?>
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
}

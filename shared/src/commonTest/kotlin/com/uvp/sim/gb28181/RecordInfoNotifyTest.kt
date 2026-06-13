package com.uvp.sim.gb28181

import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordType
import com.uvp.sim.recording.RecordingFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordInfoNotifyTest {

    private fun file(
        id: String,
        startMs: Long,
        endMs: Long,
        channelId: String = "34020000001320000001",
        type: RecordType = RecordType.Time
    ) = RecordingFile(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        durationMs = endMs - startMs,
        channelId = channelId,
        filePath = "/data/recordings/$id.mp4",
        sizeBytes = 1024L,
        thumbnailPath = null,
        source = RecordSource.Manual,
        type = type,
        secrecy = 0
    )

    @Test fun build_oneItem_xmlContainsRequiredTags() {
        val files = listOf(file("a", startMs = 1_750_000_000_000L, endMs = 1_750_000_240_000L))
        val xml = RecordInfoNotify.buildPacket(
            sn = "42",
            deviceId = "34020000001110000001",
            deviceName = "UVP-Sim",
            sumNum = 1,
            items = files
        )
        assertTrue(xml.contains("<CmdType>RecordInfo</CmdType>"))
        assertTrue(xml.contains("<SN>42</SN>"))
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
        assertTrue(xml.contains("<Name>UVP-Sim</Name>"))
        assertTrue(xml.contains("<SumNum>1</SumNum>"))
        assertTrue(xml.contains("<RecordList Num=\"1\">"))
        assertTrue(xml.contains("<DeviceID>34020000001320000001</DeviceID>"))
        assertTrue(xml.contains("<FilePath>/data/recordings/a.mp4</FilePath>"))
        assertTrue(xml.contains("<Type>time</Type>"))
        assertTrue(xml.contains("<Secrecy>0</Secrecy>"))
    }

    @Test fun build_zeroItems_emitsEmptyRecordList_sumNum0() {
        val xml = RecordInfoNotify.buildPacket(
            sn = "1",
            deviceId = "dev",
            deviceName = "n",
            sumNum = 0,
            items = emptyList()
        )
        assertTrue(xml.contains("<SumNum>0</SumNum>"))
        assertTrue(xml.contains("<RecordList Num=\"0\""))
    }

    @Test fun buildAll_paginatesInto2Packets_when55items() {
        val items = (0 until 55).map { i -> file("f$i", 1_000L * i, 1_000L * i + 500) }
        val packets = RecordInfoNotify.buildAll(
            sn = "7",
            deviceId = "dev",
            deviceName = "n",
            items = items,
            pageSize = 50
        )
        assertEquals(2, packets.size)
        // 每包都带相同 SumNum=55
        assertTrue(packets.all { it.contains("<SumNum>55</SumNum>") })
        // 第一包 50 条,第二包 5 条
        assertTrue(packets[0].contains("<RecordList Num=\"50\""))
        assertTrue(packets[1].contains("<RecordList Num=\"5\""))
    }

    @Test fun build_dateFormat_iso8601LocalNoOffset() {
        // 2024-06-13 21:30:15 UTC = 2024-06-14 05:30:15 +0800;
        // 国标默认本地时间无偏移,这里我们要求 builder 使用调用方传入的 tz。
        // 该测试用 fixed epoch 1718314215000 = 2024-06-13T21:30:15Z = 北京 2024-06-14T05:30:15
        val files = listOf(file("a", 1_718_314_215_000L, 1_718_314_245_000L))
        val xmlBeijing = RecordInfoNotify.buildPacket(
            sn = "1", deviceId = "d", deviceName = "n", sumNum = 1,
            items = files,
            timeZoneId = "Asia/Shanghai"
        )
        assertTrue(
            xmlBeijing.contains("<StartTime>2024-06-14T05:30:15</StartTime>"),
            "actual: ${xmlBeijing.lines().firstOrNull { it.contains("StartTime") }}"
        )
        assertTrue(xmlBeijing.contains("<EndTime>2024-06-14T05:30:45</EndTime>"))
    }
}

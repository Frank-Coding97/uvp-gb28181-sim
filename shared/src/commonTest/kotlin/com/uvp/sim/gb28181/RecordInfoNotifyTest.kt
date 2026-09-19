package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordType
import com.uvp.sim.recording.RecordingFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GB/T 28181 A.2.6.7 RecordInfo 应答（item 类型 A.2.1.10）。
 *
 * ⭐ 2026-09-19 补三处：
 *  - `FileSize`：**两版都有**（原先一条都不发）；
 *  - `RecordLocation` / `StreamNumber`：**只有 2022 有**，2016 分支不得多发；
 *  - `gbVersion` 参数**无默认值**，所以每个调用点都必须显式表态"我对面是哪一版"。
 */
class RecordInfoNotifyTest {

    private fun file(
        id: String,
        startMs: Long,
        endMs: Long,
        channelId: String = "34020000001320000001",
        type: RecordType = RecordType.Time,
        sizeBytes: Long = 1024L,
        streamNumber: Int = 0,
    ) = RecordingFile(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        durationMs = endMs - startMs,
        channelId = channelId,
        filePath = "/data/recordings/$id.mp4",
        sizeBytes = sizeBytes,
        thumbnailPath = null,
        source = RecordSource.Manual,
        type = type,
        secrecy = 0,
        streamNumber = streamNumber,
    )

    private fun packet(
        sn: String = "42",
        deviceId: String = "34020000001110000001",
        deviceName: String = "UVP-Sim",
        sumNum: Int = 1,
        items: List<RecordingFile>,
        gbVersion: GbVersion = GbVersion.V2022,
        timeZoneId: String = "Asia/Shanghai",
    ) = RecordInfoNotify.buildPacket(
        sn = sn, deviceId = deviceId, deviceName = deviceName,
        sumNum = sumNum, items = items, gbVersion = gbVersion, timeZoneId = timeZoneId
    )

    @Test fun build_oneItem_xmlContainsRequiredTags() {
        val files = listOf(file("a", startMs = 1_750_000_000_000L, endMs = 1_750_000_240_000L))
        val xml = packet(items = files)
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
        val xml = packet(sn = "1", deviceId = "dev", deviceName = "n", sumNum = 0, items = emptyList())
        assertTrue(xml.contains("<SumNum>0</SumNum>"))
        assertTrue(xml.contains("<RecordList Num=\"0\""))
    }

    // ---- G-12a：FileSize 两版都要发 ----

    /** `FileSize` 在 2016 与 2022 的 `itemFileType` 里**都有**，单位 Byte。原先一条都不发。 */
    @Test fun fileSize_presentInBothVersions_usesRealByteCount() {
        val f = listOf(file("a", 1_000L, 2_000L, sizeBytes = 3_145_728L))
        val xml2016 = packet(items = f, gbVersion = GbVersion.V2016)
        val xml2022 = packet(items = f, gbVersion = GbVersion.V2022)
        assertTrue(xml2016.contains("<FileSize>3145728</FileSize>"), "2016 也要发 FileSize: $xml2016")
        assertTrue(xml2022.contains("<FileSize>3145728</FileSize>"))
    }

    // ---- G-12b：RecordLocation / StreamNumber 只有 2022 ----

    /**
     * ⛔ 2016 的对端没有这两个概念，多发会让严格校验的对端判整条报文非法。
     * 这条同时钉住"版本分支真的生效了"——只测 2022 分支是测不出漏发/多发的。
     */
    @Test fun recordLocationAndStreamNumber_onlyOn2022() {
        val f = listOf(file("a", 1_000L, 2_000L, streamNumber = 1))
        val xml2016 = packet(items = f, gbVersion = GbVersion.V2016)
        val xml2022 = packet(items = f, gbVersion = GbVersion.V2022)

        assertFalse(xml2016.contains("<RecordLocation>"), "2016 不得出现 RecordLocation: $xml2016")
        assertFalse(xml2016.contains("<StreamNumber>"), "2016 不得出现 StreamNumber: $xml2016")

        assertTrue(xml2022.contains("<RecordLocation>34020000001110000001</RecordLocation>"))
        assertTrue(xml2022.contains("<StreamNumber>1</StreamNumber>"))
    }

    /**
     * `StreamNumber` 取的是**这份录像自己的**码流号，不是写死的 0 ——
     * 否则平台按子码流筛录像时会永远筛不到（G-7 过滤链的另一半）。
     */
    @Test fun streamNumber_reflectsTheRecordingItself() {
        val main = listOf(file("m", 1_000L, 2_000L, streamNumber = 0))
        val sub = listOf(file("s", 1_000L, 2_000L, streamNumber = 1))
        assertTrue(packet(items = main).contains("<StreamNumber>0</StreamNumber>"))
        assertTrue(packet(items = sub).contains("<StreamNumber>1</StreamNumber>"))
    }

    /** item 内字段顺序：FileSize 在 Type/RecorderID 之后，2022 追加的两个排最后。 */
    @Test fun itemFieldOrder_matchesItemFileType() {
        val xml = packet(items = listOf(file("a", 1_000L, 2_000L, streamNumber = 1)))
        val recorder = xml.indexOf("<RecorderID>")
        val fileSize = xml.indexOf("<FileSize>")
        val location = xml.indexOf("<RecordLocation>")
        val stream = xml.indexOf("<StreamNumber>")
        assertTrue(recorder < fileSize, "FileSize 在 RecorderID 之后")
        assertTrue(fileSize < location, "RecordLocation 在 FileSize 之后")
        assertTrue(location < stream, "StreamNumber 在 RecordLocation 之后")
    }

    @Test fun buildAll_paginatesInto2Packets_when55items() {
        val items = (0 until 55).map { i -> file("f$i", 1_000L * i, 1_000L * i + 500) }
        val packets = RecordInfoNotify.buildAll(
            sn = "7", deviceId = "dev", deviceName = "n",
            items = items, gbVersion = GbVersion.V2022, pageSize = 50
        )
        assertEquals(2, packets.size)
        assertTrue(packets.all { it.contains("<SumNum>55</SumNum>") })
        assertTrue(packets[0].contains("<RecordList Num=\"50\""))
        assertTrue(packets[1].contains("<RecordList Num=\"5\""))
    }

    @Test fun buildAll_emptyList_returnsSinglePacketWithSumNum0() {
        val packets = RecordInfoNotify.buildAll(
            sn = "1", deviceId = "d", deviceName = "n",
            items = emptyList(), gbVersion = GbVersion.V2016
        )
        assertEquals(1, packets.size)
        assertTrue(packets[0].contains("<SumNum>0</SumNum>"))
    }

    @Test fun build_dateFormat_iso8601LocalNoOffset() {
        // 1718314215000 = 2024-06-13T21:30:15Z = 北京 2024-06-14T05:30:15
        val files = listOf(file("a", 1_718_314_215_000L, 1_718_314_245_000L))
        val xmlBeijing = packet(sn = "1", deviceId = "d", deviceName = "n", items = files)
        assertTrue(
            xmlBeijing.contains("<StartTime>2024-06-14T05:30:15</StartTime>"),
            "actual: ${xmlBeijing.lines().firstOrNull { it.contains("StartTime") }}"
        )
        assertTrue(xmlBeijing.contains("<EndTime>2024-06-14T05:30:45</EndTime>"))
    }
}

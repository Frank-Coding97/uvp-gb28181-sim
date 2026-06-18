package com.uvp.sim.gb28181

import com.uvp.sim.recording.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecordInfoQueryTest {

    @Test fun parse_fullQuery_extractsAllFields() {
        val xml = """<?xml version="1.0"?>
<Query>
<CmdType>RecordInfo</CmdType>
<SN>17</SN>
<DeviceID>34020000001320000001</DeviceID>
<StartTime>2026-06-01T00:00:00</StartTime>
<EndTime>2026-06-12T23:59:59</EndTime>
<Type>time</Type>
<Secrecy>0</Secrecy>
</Query>"""
        val q = RecordInfoQuery.parse(xml, timeZoneId = "Asia/Shanghai")
        assertNotNull(q)
        assertEquals("17", q.sn)
        assertEquals("34020000001320000001", q.channelId)
        assertEquals(RecordType.Time, q.type)
        assertEquals(0, q.secrecy)
        // 2026-06-01T00:00:00+08:00 = epoch ms 1780243200000
        assertEquals(1_780_243_200_000L, q.startMs)
    }

    @Test fun parse_missingType_defaultsToNull() {
        val xml = """<Query>
<CmdType>RecordInfo</CmdType>
<SN>1</SN>
<DeviceID>d</DeviceID>
<StartTime>2026-06-01T00:00:00</StartTime>
<EndTime>2026-06-02T00:00:00</EndTime>
</Query>"""
        val q = RecordInfoQuery.parse(xml, timeZoneId = "Asia/Shanghai")
        assertNotNull(q)
        assertNull(q.type)  // 不指定类型 = 全部
    }

    @Test fun parse_missingTime_returnsNull() {
        val xml = """<Query><CmdType>RecordInfo</CmdType><SN>1</SN><DeviceID>d</DeviceID></Query>"""
        assertNull(RecordInfoQuery.parse(xml, timeZoneId = "Asia/Shanghai"))
    }

    @Test fun parse_alarmType_mapsToRecordTypeAlarm() {
        val xml = """<Query>
<CmdType>RecordInfo</CmdType>
<SN>1</SN>
<DeviceID>d</DeviceID>
<StartTime>2026-06-01T00:00:00</StartTime>
<EndTime>2026-06-02T00:00:00</EndTime>
<Type>alarm</Type>
</Query>"""
        val q = RecordInfoQuery.parse(xml, timeZoneId = "Asia/Shanghai")
        assertEquals(RecordType.Alarm, q?.type)
    }

    // M5 batch2 §3.11 — 高级过滤字段(plan §Q3 仅解析透传,不参与命中)

    @Test fun parse_advancedFilters_allFour() {
        val xml = """<Query>
<CmdType>RecordInfo</CmdType>
<SN>1</SN>
<DeviceID>d</DeviceID>
<StartTime>2026-06-01T00:00:00</StartTime>
<EndTime>2026-06-02T00:00:00</EndTime>
<IndistinctQuery>1</IndistinctQuery>
<FilePath>/rec/x.mp4</FilePath>
<Address>192.168.1.1</Address>
<RecorderID>R001</RecorderID>
</Query>"""
        val q = RecordInfoQuery.parse(xml, timeZoneId = "Asia/Shanghai")
        assertNotNull(q)
        assertEquals(1, q.indistinctQuery)
        assertEquals("/rec/x.mp4", q.filePath)
        assertEquals("192.168.1.1", q.address)
        assertEquals("R001", q.recorderId)
    }

    @Test fun parse_advancedFilters_onlyIndistinct() {
        val xml = """<Query>
<CmdType>RecordInfo</CmdType>
<SN>1</SN>
<DeviceID>d</DeviceID>
<StartTime>2026-06-01T00:00:00</StartTime>
<EndTime>2026-06-02T00:00:00</EndTime>
<IndistinctQuery>1</IndistinctQuery>
</Query>"""
        val q = RecordInfoQuery.parse(xml, timeZoneId = "Asia/Shanghai")
        assertNotNull(q)
        assertEquals(1, q.indistinctQuery)
        assertNull(q.filePath)
        assertNull(q.address)
        assertNull(q.recorderId)
    }

    @Test fun parse_advancedFilters_legacyXmlBackwardCompatible() {
        // 旧 XML 不带新字段,4 字段全部默认值
        val xml = """<Query>
<CmdType>RecordInfo</CmdType>
<SN>1</SN>
<DeviceID>d</DeviceID>
<StartTime>2026-06-01T00:00:00</StartTime>
<EndTime>2026-06-02T00:00:00</EndTime>
<Type>time</Type>
<Secrecy>0</Secrecy>
</Query>"""
        val q = RecordInfoQuery.parse(xml, timeZoneId = "Asia/Shanghai")
        assertNotNull(q)
        assertEquals(0, q.indistinctQuery)
        assertNull(q.filePath)
        assertNull(q.address)
        assertNull(q.recorderId)
    }

    @Test fun parse_indistinctQueryNonNumeric_degradesTo0() {
        val xml = """<Query>
<CmdType>RecordInfo</CmdType>
<SN>1</SN>
<DeviceID>d</DeviceID>
<StartTime>2026-06-01T00:00:00</StartTime>
<EndTime>2026-06-02T00:00:00</EndTime>
<IndistinctQuery>yes</IndistinctQuery>
</Query>"""
        val q = RecordInfoQuery.parse(xml, timeZoneId = "Asia/Shanghai")
        assertEquals(0, q?.indistinctQuery)
    }

    @Test fun parse_blankStringFields_returnsNull() {
        val xml = """<Query>
<CmdType>RecordInfo</CmdType>
<SN>1</SN>
<DeviceID>d</DeviceID>
<StartTime>2026-06-01T00:00:00</StartTime>
<EndTime>2026-06-02T00:00:00</EndTime>
<FilePath>   </FilePath>
<Address></Address>
</Query>"""
        val q = RecordInfoQuery.parse(xml, timeZoneId = "Asia/Shanghai")
        assertNotNull(q)
        assertNull(q.filePath)
        assertNull(q.address)
    }
}

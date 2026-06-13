package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManscdpParserRecordCmdTest {

    @Test fun recordCmd_record_returnsRecord() {
        val xml = """<?xml version="1.0"?>
<Control>
<CmdType>DeviceControl</CmdType>
<SN>17</SN>
<DeviceID>34020000001320000001</DeviceID>
<RecordCmd>Record</RecordCmd>
</Control>"""
        assertEquals("Record", ManscdpParser.recordCmd(xml))
    }

    @Test fun recordCmd_stopRecord_returnsStopRecord() {
        val xml = """<Control>
<CmdType>DeviceControl</CmdType>
<RecordCmd>StopRecord</RecordCmd>
</Control>"""
        assertEquals("StopRecord", ManscdpParser.recordCmd(xml))
    }

    @Test fun recordCmd_missing_returnsNull() {
        val xml = """<Control>
<CmdType>DeviceControl</CmdType>
<SN>1</SN>
</Control>"""
        assertNull(ManscdpParser.recordCmd(xml))
    }

    @Test fun recordCmd_caseSensitive_returnsExactlyAsXml() {
        // GB28181 RecordCmd 值标准是 "Record"/"StopRecord",但平台实现可能有大小写差异。
        // 我们如实返回,SimulatorEngine 自己决定怎么 match。
        val xml = "<X><RecordCmd>record</RecordCmd></X>"
        assertEquals("record", ManscdpParser.recordCmd(xml))
    }

    @Test fun recordCmd_whitespaceTrimmed() {
        val xml = "<X><RecordCmd>  Record  </RecordCmd></X>"
        assertEquals("Record", ManscdpParser.recordCmd(xml))
    }
}

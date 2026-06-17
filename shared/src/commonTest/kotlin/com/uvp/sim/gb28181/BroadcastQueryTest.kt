package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T1 — Broadcast MESSAGE body 解析(SourceID / TargetID / SN)。 */
class BroadcastQueryTest {

    @Test
    fun extractsSourceIdAndTargetId() {
        val xml = """<?xml version="1.0"?><Notify>
            <CmdType>Broadcast</CmdType>
            <SN>1</SN>
            <SourceID>34020000002000000001</SourceID>
            <TargetID>34020000001320000001</TargetID>
        </Notify>""".trimIndent()
        assertEquals("34020000002000000001", ManscdpParser.sourceId(xml))
        assertEquals("34020000001320000001", ManscdpParser.targetId(xml))
    }

    @Test
    fun missingFieldReturnsNull() {
        val xml = "<Notify><CmdType>Broadcast</CmdType></Notify>"
        assertNull(ManscdpParser.sourceId(xml))
        assertNull(ManscdpParser.targetId(xml))
    }

    @Test
    fun broadcastQueryParseWrapsAllFields() {
        val xml = """<Notify>
            <CmdType>Broadcast</CmdType>
            <SN>7</SN>
            <SourceID>34020000002000000001</SourceID>
            <TargetID>34020000001320000001</TargetID>
        </Notify>""".trimIndent()
        val q = BroadcastQuery.parse(xml)
        assertEquals("7", q.sn)
        assertEquals("34020000002000000001", q.sourceId)
        assertEquals("34020000001320000001", q.targetId)
    }
}

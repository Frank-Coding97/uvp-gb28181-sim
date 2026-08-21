package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogIncrementalNotifyTest {

    private val rootId = "34020000001310000001"

    @Test
    fun emptyEventsBuildsEmptyDeviceList() {
        val xml = CatalogNotifyBuilder.buildIncremental(rootId, sn = 1, events = emptyList())
        assertTrue(xml.contains("<CmdType>Catalog</CmdType>"))
        assertTrue(xml.contains("<SumNum>0</SumNum>"))
        assertTrue(xml.contains("<DeviceList Num=\"0\""))
    }

    @Test
    fun addEventEmitsAddItem() {
        val node = CatalogNode("v1", CatalogNodeType.VideoChannel, "新通道", rootId)
        val xml = CatalogNotifyBuilder.buildIncremental(
            rootId, sn = 1,
            events = listOf(CatalogChangeEvent.Add(node))
        )
        assertTrue(xml.contains("<Event>ADD</Event>"))
        assertTrue(xml.contains("<DeviceID>v1</DeviceID>"))
        assertTrue(xml.contains("<Name>新通道</Name>"))
        assertTrue(xml.contains("<SumNum>1</SumNum>"))
    }

    @Test
    fun delEventEmitsMinimalItem() {
        val xml = CatalogNotifyBuilder.buildIncremental(
            rootId, sn = 1,
            events = listOf(CatalogChangeEvent.Del("v1"))
        )
        assertTrue(xml.contains("<Event>DEL</Event>"))
        assertTrue(xml.contains("<DeviceID>v1</DeviceID>"))
        // DEL 只发 DeviceID + Event,不带 Name/Status 等
        assertTrue(!xml.contains("<Name>"), "DEL Item 不应有 Name")
    }

    @Test
    fun updateEventEmitsUpdateItem() {
        val node = CatalogNode("v1", CatalogNodeType.VideoChannel, "改名", rootId)
        val xml = CatalogNotifyBuilder.buildIncremental(
            rootId, sn = 1,
            events = listOf(CatalogChangeEvent.Update(node))
        )
        assertTrue(xml.contains("<Event>UPDATE</Event>"))
        assertTrue(xml.contains("<Name>改名</Name>"))
    }

    @Test
    fun mixedEventsCorrectOrder() {
        val v1 = CatalogNode("v1", CatalogNodeType.VideoChannel, "新", rootId)
        val v2 = CatalogNode("v2", CatalogNodeType.VideoChannel, "改", rootId)
        val xml = CatalogNotifyBuilder.buildIncremental(
            rootId, sn = 1,
            events = listOf(
                CatalogChangeEvent.Add(v1),
                CatalogChangeEvent.Update(v2),
                CatalogChangeEvent.Del("v3")
            )
        )
        assertTrue(xml.contains("<SumNum>3</SumNum>"))
        // 三个 Event 标签都出现
        assertTrue(xml.contains("<Event>ADD</Event>"))
        assertTrue(xml.contains("<Event>UPDATE</Event>"))
        assertTrue(xml.contains("<Event>DEL</Event>"))
        // Add v1 在 Update v2 之前
        assertTrue(xml.indexOf("v1") < xml.indexOf("v2"))
    }

    @Test
    fun multiResponsePacketsShareSnAndTotalCount() {
        val events = listOf(
            CatalogChangeEvent.Add(CatalogNode("v1", CatalogNodeType.VideoChannel, "一", rootId)),
            CatalogChangeEvent.Update(CatalogNode("v2", CatalogNodeType.VideoChannel, "二", rootId)),
            CatalogChangeEvent.Del("v3"),
        )

        val packets = CatalogNotifyBuilder.buildIncrementalAll(rootId, sn = 8, events = events, pageSize = 2)

        assertEquals(2, packets.size)
        assertTrue(packets.all { it.contains("<SN>8</SN>") })
        assertTrue(packets.all { it.contains("<SumNum>3</SumNum>") })
        assertTrue(packets[0].contains("<DeviceList Num=\"2\">"))
        assertTrue(packets[1].contains("<DeviceList Num=\"1\">"))
    }

    @Test
    fun crlfLineEndings() {
        val xml = CatalogNotifyBuilder.buildIncremental(rootId, sn = 1, events = emptyList())
        assertTrue(xml.contains("\r\n"))
    }

    @Test
    fun snAndDeviceIdInjected() {
        val xml = CatalogNotifyBuilder.buildIncremental(rootId, sn = 42, events = emptyList())
        assertTrue(xml.contains("<SN>42</SN>"))
        assertTrue(xml.contains("<DeviceID>$rootId</DeviceID>"))
    }
}

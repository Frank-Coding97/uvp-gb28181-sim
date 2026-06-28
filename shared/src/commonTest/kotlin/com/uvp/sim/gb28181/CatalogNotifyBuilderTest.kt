package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogNotifyBuilderTest {

    private val rootId = "34020000001110000001"

    private fun root(name: String = "Cam") =
        CatalogNode(rootId, CatalogNodeType.Device, name, rootId)

    @Test
    fun `build emits Notify wrapper with CmdType Catalog`() {
        val xml = CatalogNotifyBuilder.build(rootId, 42, listOf(root()))
        assertTrue(xml.contains("<Notify>"))
        assertTrue(xml.contains("</Notify>"))
        assertTrue(xml.contains("<CmdType>Catalog</CmdType>"))
        assertTrue(xml.contains("<SN>42</SN>"))
        assertTrue(xml.contains("<DeviceID>$rootId</DeviceID>"))
    }

    @Test
    fun `build emits DeviceList Num matching tree size`() {
        val tree = listOf(
            root(),
            CatalogNode("34020000001370000001", CatalogNodeType.BusinessGroup, "G1", rootId),
            CatalogNode("34020000001320000001", CatalogNodeType.VideoChannel, "V1", rootId)
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree)
        assertTrue(xml.contains("<SumNum>3</SumNum>"))
        assertTrue(xml.contains("<DeviceList Num=\"3\">"))
    }

    @Test
    fun `build empty tree emits Num zero element`() {
        val xml = CatalogNotifyBuilder.build(rootId, 1, emptyList())
        assertTrue(xml.contains("<SumNum>0</SumNum>"))
        assertTrue(xml.contains("<DeviceList Num=\"0\">"))
    }

    @Test
    fun `build emits Parental 1 for Device, BusinessGroup, VirtualOrg`() {
        val tree = listOf(
            root(),
            CatalogNode("g1", CatalogNodeType.BusinessGroup, "G", rootId),
            CatalogNode("v1", CatalogNodeType.VirtualOrg, "V", rootId)
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree)
        // 三个 Item 都是 Parental=1
        val parentalCount = "<Parental>1</Parental>".toRegex().findAll(xml).count()
        assertEquals(3, parentalCount)
    }

    @Test
    fun `build emits Parental 0 for VideoChannel and AlarmChannel`() {
        val tree = listOf(
            CatalogNode("ch1", CatalogNodeType.VideoChannel, "V", rootId),
            CatalogNode("ch2", CatalogNodeType.AlarmChannel, "A", rootId)
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree)
        val parental0 = "<Parental>0</Parental>".toRegex().findAll(xml).count()
        assertEquals(2, parental0)
    }

    @Test
    fun `build root ParentID equals own DeviceID`() {
        val xml = CatalogNotifyBuilder.build(rootId, 1, listOf(root()))
        // 找根 Item:DeviceID=rootId 的 Item 内 ParentID 也应是 rootId
        val rootItemRegex = """<Item>.*?<DeviceID>$rootId</DeviceID>.*?<ParentID>(.+?)</ParentID>.*?</Item>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = rootItemRegex.find(xml)
        assertTrue(match != null, "root item present")
        assertEquals(rootId, match!!.groupValues[1])
    }

    @Test
    fun `build DFS order parent before child for nested tree`() {
        // 树结构:
        //   root
        //   ├── group1
        //   │   ├── ch1 (video)
        //   │   └── ch2 (alarm)
        //   └── group2
        //       └── ch3 (video)
        val tree = listOf(
            CatalogNode(rootId, CatalogNodeType.Device, "Root", rootId),
            CatalogNode("group1", CatalogNodeType.BusinessGroup, "G1", rootId),
            CatalogNode("ch1", CatalogNodeType.VideoChannel, "C1", "group1"),
            CatalogNode("ch2", CatalogNodeType.AlarmChannel, "C2", "group1"),
            CatalogNode("group2", CatalogNodeType.BusinessGroup, "G2", rootId),
            CatalogNode("ch3", CatalogNodeType.VideoChannel, "C3", "group2")
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree)

        // 抽出 Item DeviceID 出现顺序
        val ids = """<Item>\s*<DeviceID>(.+?)</DeviceID>"""
            .toRegex()
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(6, ids.size)

        // 父先于子
        assertTrue(ids.indexOf(rootId) < ids.indexOf("group1"))
        assertTrue(ids.indexOf("group1") < ids.indexOf("ch1"))
        assertTrue(ids.indexOf("group1") < ids.indexOf("ch2"))
        assertTrue(ids.indexOf(rootId) < ids.indexOf("group2"))
        assertTrue(ids.indexOf("group2") < ids.indexOf("ch3"))
    }

    @Test
    fun `build emits CRLF line endings`() {
        val xml = CatalogNotifyBuilder.build(rootId, 1, listOf(root()))
        assertTrue(xml.contains("\r\n"), "XML should use CRLF line endings")
    }

    @Test
    fun `build emits ChildItem ParentID pointing to actual parent`() {
        val tree = listOf(
            CatalogNode(rootId, CatalogNodeType.Device, "Root", rootId),
            CatalogNode("group1", CatalogNodeType.BusinessGroup, "G1", rootId),
            CatalogNode("ch1", CatalogNodeType.VideoChannel, "C1", "group1")
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree)
        val ch1Regex = """<Item>.*?<DeviceID>ch1</DeviceID>.*?<ParentID>(.+?)</ParentID>.*?</Item>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = ch1Regex.find(xml)
        assertTrue(match != null)
        assertEquals("group1", match!!.groupValues[1])
    }

    @Test
    fun `build uses field overrides when present`() {
        val node = CatalogNode(
            "ch1",
            CatalogNodeType.VideoChannel,
            "Cam",
            rootId,
            fields = mapOf(
                "Manufacturer" to "Hikvision",
                "Model" to "DS-2CD",
                "Status" to "OFF"
            )
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, listOf(node))
        assertTrue(xml.contains("<Manufacturer>Hikvision</Manufacturer>"))
        assertTrue(xml.contains("<Model>DS-2CD</Model>"))
        assertTrue(xml.contains("<Status>OFF</Status>"))
    }

    @Test
    fun `build escapes special characters in name and fields to prevent XML injection`() {
        val node = CatalogNode(
            "ch1",
            CatalogNodeType.VideoChannel,
            "Cam &<>\"' Inject",
            rootId,
            fields = mapOf(
                "Manufacturer" to "A&B",
                "Owner" to "</Item><Item><DeviceID>injected</DeviceID></Item>"
            )
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, listOf(node))
        // 转义后 5 字符全没有原始形态
        assertTrue(xml.contains("<Name>Cam &amp;&lt;&gt;&quot;&apos; Inject</Name>"))
        assertTrue(xml.contains("<Manufacturer>A&amp;B</Manufacturer>"))
        // 注入企图应被转义,不出现新的 <Item>
        assertTrue(xml.contains("&lt;/Item&gt;"))
        assertTrue(!xml.contains("<DeviceID>injected</DeviceID>"), "injection must not produce an extra Item")
        // 仍只有 1 个 Item
        val itemCount = "<Item>".toRegex().findAll(xml).count()
        assertEquals(1, itemCount)
    }
}

package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.GbVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogNotifyBuilderTest {

    private val rootId = "34020000001110000001"

    private fun root(name: String = "Cam") =
        CatalogNode(rootId, CatalogNodeType.Device, name, rootId)

    @Test
    fun `build emits Notify wrapper with CmdType Catalog`() {
        val xml = CatalogNotifyBuilder.build(rootId, 42, listOf(root()), version = GbVersion.V2022)
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
            CatalogNode("34020000002150000001", CatalogNodeType.BusinessGroup, "G1", rootId),
            CatalogNode("34020000001320000001", CatalogNodeType.VideoChannel, "V1", rootId)
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree, version = GbVersion.V2022)
        assertTrue(xml.contains("<SumNum>3</SumNum>"))
        assertTrue(xml.contains("<DeviceList Num=\"3\">"))
    }

    @Test
    fun `buildAll splits packets with same SN and total count`() {
        val tree = listOf(
            root(),
            CatalogNode("group1", CatalogNodeType.BusinessGroup, "G1", rootId),
            CatalogNode("ch1", CatalogNodeType.VideoChannel, "C1", "group1"),
            CatalogNode("ch2", CatalogNodeType.VideoChannel, "C2", "group1"),
            CatalogNode("ch3", CatalogNodeType.VideoChannel, "C3", "group1"),
        )

        val packets = CatalogNotifyBuilder.buildAll(rootId, 17, tree, pageSize = 2, version = GbVersion.V2022)

        assertEquals(3, packets.size)
        assertTrue(packets.all { it.contains("<SN>17</SN>") })
        assertTrue(packets.all { it.contains("<SumNum>5</SumNum>") })
        assertTrue(packets[0].contains("<DeviceList Num=\"2\">"))
        assertTrue(packets[1].contains("<DeviceList Num=\"2\">"))
        assertTrue(packets[2].contains("<DeviceList Num=\"1\">"))
    }

    @Test
    fun `build empty tree omits DeviceList`() {
        val xml = CatalogNotifyBuilder.build(rootId, 1, emptyList(), version = GbVersion.V2022)
        assertTrue(xml.contains("<SumNum>0</SumNum>"))
        assertTrue(!xml.contains("<DeviceList"))
    }

    @Test
    fun `build omits Parental from business group and virtual organization items`() {
        val tree = listOf(
            root(),
            CatalogNode("g1", CatalogNodeType.BusinessGroup, "G", rootId),
            CatalogNode("v1", CatalogNodeType.VirtualOrg, "V", rootId)
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree, version = GbVersion.V2022)
        // 附录 J 的业务分组/虚拟组织 Item 不输出 Parental；Device 根保留其设备目录字段。
        val parentalCount = "<Parental>1</Parental>".toRegex().findAll(xml).count()
        assertEquals(1, parentalCount)
    }

    @Test
    fun `build emits Parental 0 for VideoChannel and AlarmChannel`() {
        val tree = listOf(
            CatalogNode("ch1", CatalogNodeType.VideoChannel, "V", rootId),
            CatalogNode("ch2", CatalogNodeType.AlarmChannel, "A", rootId)
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree, version = GbVersion.V2022)
        val parental0 = "<Parental>0</Parental>".toRegex().findAll(xml).count()
        assertEquals(2, parental0)
    }

    @Test
    fun `build root ParentID equals own DeviceID`() {
        val xml = CatalogNotifyBuilder.build(rootId, 1, listOf(root()), version = GbVersion.V2022)
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
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree, version = GbVersion.V2022)

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
        val xml = CatalogNotifyBuilder.build(rootId, 1, listOf(root()), version = GbVersion.V2022)
        assertTrue(xml.contains("\r\n"), "XML should use CRLF line endings")
    }

    @Test
    fun `build emits ChildItem ParentID pointing to actual parent`() {
        val tree = listOf(
            CatalogNode(rootId, CatalogNodeType.Device, "Root", rootId),
            CatalogNode("group1", CatalogNodeType.BusinessGroup, "G1", rootId),
            CatalogNode("ch1", CatalogNodeType.VideoChannel, "C1", "group1")
        )
        val xml = CatalogNotifyBuilder.build(rootId, 1, tree, version = GbVersion.V2022)
        val ch1Regex = """<Item>.*?<DeviceID>ch1</DeviceID>.*?<ParentID>(.+?)</ParentID>.*?</Item>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = ch1Regex.find(xml)
        assertTrue(match != null)
        assertEquals("group1", match!!.groupValues[1])
    }

    @Test
    fun `build emits BusinessGroupID for virtual organization`() {
        val groupId = "34020000002150000001"
        val orgId = "34020000002160000001"
        val tree = listOf(
            root(),
            CatalogNode(groupId, CatalogNodeType.BusinessGroup, "重点场所", rootId),
            CatalogNode(orgId, CatalogNodeType.VirtualOrg, "校园", groupId),
        )

        val xml = CatalogNotifyBuilder.build(rootId, 1, tree, version = GbVersion.V2022)
        val orgItem = """<Item>.*?<DeviceID>$orgId</DeviceID>.*?</Item>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.value.orEmpty()
        assertTrue(orgItem.contains("<BusinessGroupID>$groupId</BusinessGroupID>"))
    }

    @Test
    fun `build emits explicitly selected BusinessGroupID for channel`() {
        val groupId = "34020000002150000001"
        val channelId = "34020000001320000001"
        val tree = listOf(
            root(),
            CatalogNode(groupId, CatalogNodeType.BusinessGroup, "重点场所", rootId),
            CatalogNode(
                channelId,
                CatalogNodeType.VideoChannel,
                "门岗摄像机",
                rootId,
                fields = mapOf("BusinessGroupID" to groupId)
            )
        )

        val xml = CatalogNotifyBuilder.build(rootId, 1, tree, version = GbVersion.V2022)
        val channelItem = """<Item>.*?<DeviceID>$channelId</DeviceID>.*?</Item>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.value.orEmpty()
        assertTrue(channelItem.contains("<ParentID>$rootId</ParentID>"))
        assertTrue(channelItem.contains("<BusinessGroupID>$groupId</BusinessGroupID>"))
    }

    @Test
    fun `build follows appendix J for first level virtual org and multi parent channel`() {
        val systemId = "34020000002000000001"
        val groupId = "34020000002150000001"
        val orgId = "34020000002160000001"
        val channelId = "34020000001320000001"
        val tree = listOf(
            CatalogNode(systemId, CatalogNodeType.System, "下级平台", systemId),
            CatalogNode(groupId, CatalogNodeType.BusinessGroup, "校园安防", systemId),
            CatalogNode(
                orgId,
                CatalogNodeType.VirtualOrg,
                "出入口",
                "",
                fields = mapOf("BusinessGroupID" to groupId),
            ),
            CatalogNode(channelId, CatalogNodeType.VideoChannel, "东门摄像头", "$systemId/$orgId"),
        )

        val xml = CatalogNotifyBuilder.build(systemId, 1, tree, version = GbVersion.V2022)
        val items = """<Item>.*?</Item>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.value }.toList()
        fun item(id: String) = items.firstOrNull { it.contains("<DeviceID>$id</DeviceID>") }.orEmpty()
        val orgItem = item(orgId)
        val channelItem = item(channelId)

        assertTrue(orgItem.contains("<BusinessGroupID>$groupId</BusinessGroupID>"))
        assertEquals(
            1,
            "<BusinessGroupID>$groupId</BusinessGroupID>".toRegex().findAll(orgItem).count(),
            "每个 216 目录项只能输出一个 BusinessGroupID",
        )
        assertTrue(!orgItem.contains("<ParentID>"), "首层216不应伪造 ParentID")
        assertTrue(channelItem.contains("<ParentID>$systemId/$orgId</ParentID>"))
        assertTrue(xml.indexOf("<DeviceID>$groupId</DeviceID>") < xml.indexOf("<DeviceID>$orgId</DeviceID>"))
        assertTrue(xml.indexOf("<DeviceID>$orgId</DeviceID>") < xml.indexOf("<DeviceID>$channelId</DeviceID>"))
    }

    @Test
    fun `appendix J system business group and virtual org only emit item type fields`() {
        val systemId = "34020000002000000001"
        val groupId = "34020000002150000001"
        val orgId = "34020000002160000001"
        val tree = listOf(
            CatalogNode(systemId, CatalogNodeType.System, "下级平台", systemId),
            CatalogNode(groupId, CatalogNodeType.BusinessGroup, "校园安防", systemId),
            CatalogNode(orgId, CatalogNodeType.VirtualOrg, "出入口", "", mapOf("BusinessGroupID" to groupId)),
        )

        val xml = CatalogNotifyBuilder.build(systemId, 1, tree, version = GbVersion.V2022)
        val items = """<Item>.*?</Item>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.value }.toList()
        fun item(id: String) = items.firstOrNull { it.contains("<DeviceID>$id</DeviceID>") }.orEmpty()
        val system = item(systemId)
        val group = item(groupId)
        val org = item(orgId)

        assertTrue(!system.contains("<ParentID>"))
        assertTrue(!system.contains("<Parental>"))
        assertTrue(group.contains("<ParentID>$systemId</ParentID>"))
        assertTrue(!group.contains("<Parental>") && !group.contains("<Status>"))
        assertTrue(!group.contains("<Manufacturer>") && !group.contains("<Owner>"))
        assertTrue(org.contains("<BusinessGroupID>$groupId</BusinessGroupID>"))
        assertTrue(!org.contains("<ParentID>") && !org.contains("<Parental>"))
        assertTrue(!org.contains("<Status>") && !org.contains("<Info>"))
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
        val xml = CatalogNotifyBuilder.build(rootId, 1, listOf(node), version = GbVersion.V2022)
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
        val xml = CatalogNotifyBuilder.build(rootId, 1, listOf(node), version = GbVersion.V2022)
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

    /**
     * 2026-10-24 现场回归：UDP 上一条 2.5KB 的 Catalog 应答**整体发不出去**（设备 send 抛错，
     * 平台侧一个分片都收不到），而 371B 的 200 OK 次次正常。所以按字节切包，别指望 IP 分片。
     */
    @Test
    fun `buildAll honours maxBytes and keeps SumNum intact`() {
        val tree = listOf(root()) + (1..6).map { i ->
            CatalogNode("3402000000132000000$i", CatalogNodeType.VideoChannel, "通道$i", rootId)
        }
        val maxBytes = 900
        val packets = CatalogNotifyBuilder.buildAll(
            rootId, 21, tree, pageSize = 50, version = GbVersion.V2022, maxBytes = maxBytes,
        )

        assertTrue(packets.size > 1, "6 条通道 + 900B 上限必须切多包")
        // 每包都不超上限（单条本身超限时允许独占一包，这里单条远小于上限）
        packets.forEach { p ->
            val bytes = p.encodeToByteArray().size
            assertTrue(bytes <= maxBytes, "包长 $bytes 超过上限 $maxBytes")
        }
        // 所有包共享全量 SumNum，且各包条目数加起来等于树节点数
        assertTrue(packets.all { it.contains("<SumNum>${tree.size}</SumNum>") })
        val itemTotal = packets.sumOf { "<Item>".toRegex().findAll(it).count() }
        assertEquals(tree.size, itemTotal)
        // 同一 SN
        assertTrue(packets.all { it.contains("<SN>21</SN>") })
    }
}

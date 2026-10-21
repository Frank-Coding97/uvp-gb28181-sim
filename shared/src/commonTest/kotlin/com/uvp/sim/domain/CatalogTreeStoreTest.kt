package com.uvp.sim.domain

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CatalogTreeStoreTest {

    private fun cfg(catalogTree: List<CatalogNode> = emptyList()) = SimConfig(
        server = ServerConfig(ip = "1.2.3.4", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            name = "TestCam",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "u",
            password = "p"
        ),
        catalogTree = catalogTree
    )

    @Test
    fun `defaultTree builds 3 nodes from device fields`() {
        val tree = CatalogTreeStore.defaultTree(cfg())
        assertEquals(3, tree.size)

        val root = tree[0]
        assertEquals("34020000001110000001", root.id)
        assertEquals(CatalogNodeType.Device, root.type)
        assertEquals("TestCam", root.name)
        assertEquals("34020000001110000001", root.parentId, "root parentId points to itself")

        val video = tree[1]
        assertEquals(CatalogNodeType.VideoChannel, video.type)
        assertEquals("34020000001320000001", video.id)
        assertEquals(root.id, video.parentId)

        val alarm = tree[2]
        assertEquals(CatalogNodeType.AlarmChannel, alarm.type)
        assertEquals("34020000001340000001", alarm.id)
        assertEquals(root.id, alarm.parentId)
    }

    @Test
    fun `effectiveTree returns default when catalogTree is empty`() {
        val tree = CatalogTreeStore.effectiveTree(cfg())
        assertEquals(3, tree.size)
    }

    @Test
    fun `effectiveTree returns user tree when present`() {
        val custom = listOf(
            CatalogNode("rootId", CatalogNodeType.Device, "X", "rootId"),
            CatalogNode("g1", CatalogNodeType.BusinessGroup, "G1", "rootId")
        )
        val result = CatalogTreeStore.effectiveTree(cfg(custom))
        assertEquals(2, result.size)
        assertSame(custom, result)
    }

    @Test
    fun `default tree has Status fields populated`() {
        val tree = CatalogTreeStore.defaultTree(cfg())
        assertTrue(tree.all { it.fields["Status"] == "ON" }, "all nodes have Status=ON")
    }

    @Test
    fun `position channel keeps configured video channel when it is in active tree`() {
        val tree = listOf(
            CatalogNode("root", CatalogNodeType.Device, "X", "root"),
            CatalogNode("other", CatalogNodeType.VideoChannel, "Other", "root"),
            CatalogNode("34020000001320000001", CatalogNodeType.VideoChannel, "Configured", "root")
        )
        assertEquals("34020000001320000001", CatalogTreeStore.positionChannelId(cfg(tree), tree))
    }

    @Test
    fun `position channel falls back to first active video channel after catalog migration`() {
        val tree = listOf(
            CatalogNode("root", CatalogNodeType.Device, "X", "root"),
            CatalogNode("34020000001320000010", CatalogNodeType.VideoChannel, "Current", "root")
        )
        assertEquals("34020000001320000010", CatalogTreeStore.positionChannelId(cfg(tree), tree))
    }

    @Test
    fun `position channel falls back to configured id when active tree has no video channel`() {
        val tree = listOf(CatalogNode("root", CatalogNodeType.Device, "X", "root"))
        assertEquals("34020000001320000001", CatalogTreeStore.positionChannelId(cfg(tree), tree))
    }

    @Test
    fun `standard business group template follows GB 2022 appendix J relations`() {
        val base = cfg()
        val config = base.copy(
            device = base.device.copy(
                frontChannelId = "34020000001320000002",
                videoChannelId = "34020000001320000001",
                alarmChannelId = "34020000001340000001",
            )
        )
        val template = CatalogTreeStore.standardBusinessGroupTemplate(config)
        val root = template.first()
        val group = template.first { it.type == CatalogNodeType.BusinessGroup }
        val firstOrg = template.first { it.type == CatalogNodeType.VirtualOrg && it.parentId.isBlank() }
        val nestedOrg = template.first { it.type == CatalogNodeType.VirtualOrg && it.parentId.isNotBlank() }
        val channels = template.filter { it.type == CatalogNodeType.VideoChannel }

        assertEquals(CatalogNodeType.System, root.type)
        assertEquals(root.id, root.parentId)
        assertEquals("200", root.id.substring(10, 13))
        assertEquals(root.id, group.parentId)
        assertEquals(group.id, firstOrg.fields["BusinessGroupID"])
        assertTrue(firstOrg.parentId.isBlank(), "首层216按附录J不填写 ParentID")
        assertEquals(firstOrg.id, nestedOrg.parentId)
        assertEquals(group.id, nestedOrg.fields["BusinessGroupID"])
        assertEquals(setOf(config.device.frontChannelId, config.device.videoChannelId), channels.map { it.id }.toSet())
        assertTrue(channels.all { it.parentId.startsWith(root.id + "/") })
        assertEquals(ValidationResult.Ok, CatalogTreeStore.validate(template))
    }

    @Test
    fun `standard business group template omits missing front channel`() {
        val config = cfg()
        val template = CatalogTreeStore.standardBusinessGroupTemplate(config)

        assertTrue(template.none { it.id.isBlank() })
        assertEquals(1, template.count { it.type == CatalogNodeType.VideoChannel })
        assertEquals(config.device.videoChannelId, template.single { it.type == CatalogNodeType.VideoChannel }.id)
        assertEquals(ValidationResult.Ok, CatalogTreeStore.validate(template))
    }

    @Test
    fun `standard business group template is only offered for GB 2022`() {
        val config = cfg().copy(gbVersion = com.uvp.sim.config.GbVersion.V2016)

        assertTrue(CatalogTreeStore.templates(config).none { it.id == "standard-business-group" })
        assertEquals(
            "34020000001110000001",
            CatalogTreeStore.standardDeviceId("34020000002000000001"),
        )
    }

    @Test
    fun `templates expose only supported catalog examples`() {
        assertEquals(
            listOf("single", "standard-business-group"),
            CatalogTreeStore.templates(cfg().copy(gbVersion = com.uvp.sim.config.GbVersion.V2022)).map { it.id },
        )
        assertEquals(
            listOf("single"),
            CatalogTreeStore.templates(cfg().copy(gbVersion = com.uvp.sim.config.GbVersion.V2016)).map { it.id },
        )
    }

    @Test
    fun `single template description matches actual video channel count`() {
        val oneChannel = CatalogTreeStore.templates(cfg()).single { it.id == "single" }
        assertEquals("1 设备 + 1 视频通道 + 1 报警通道。最简结构。", oneChannel.description)

        val base = cfg()
        val twoChannelConfig = base.copy(
            device = base.device.copy(frontChannelId = "34020000001320000002"),
        )
        val twoChannels = CatalogTreeStore.templates(twoChannelConfig).single { it.id == "single" }
        assertEquals(2, twoChannels.nodes.count { it.type == CatalogNodeType.VideoChannel })
        assertEquals("1 设备 + 2 视频通道 + 1 报警通道。最简结构。", twoChannels.description)
    }
}

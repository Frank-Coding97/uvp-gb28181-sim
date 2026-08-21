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
}

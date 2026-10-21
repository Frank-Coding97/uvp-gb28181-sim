package com.uvp.sim.ui.capability.catalog

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BusinessCatalogEditorTest {
    private val system = CatalogNode("37010301022000000007", CatalogNodeType.System, "系统", "37010301022000000007")
    private val group = CatalogNode("37010301022150000001", CatalogNodeType.BusinessGroup, "分组", system.id)
    private val org = CatalogNode("37010301022160000001", CatalogNodeType.VirtualOrg, "门岗", "", mapOf("BusinessGroupID" to group.id))
    private val nested = CatalogNode("37010301022160000002", CatalogNodeType.VirtualOrg, "东门", org.id, org.fields)
    private val camera = CatalogNode("34020000001320000010", CatalogNodeType.VideoChannel, "后摄", "${system.id}/${nested.id}")
    private val tree = listOf(system, group, org, nested, camera)

    @Test fun businessRelationsDriveDisplayAndSearch() {
        assertEquals(listOf(0, 1, 2, 3, 4), tree.map { buildDepthMap(tree)[it.id] })
        assertEquals(tree, filterTreeByQuery(tree, "后摄"))
        assertEquals(setOf(org.id, nested.id, camera.id), collectDescendants(group.id, tree))
        assertFalse(canMove(org, nested.id, tree))
        assertFalse(canMove(system, org.id, tree))
    }

    @Test fun movingCameraPreservesPhysicalParent() {
        val moved = moveCatalogNode(camera, org, tree)
        assertEquals("${system.id}/${org.id}", moved.first { it.id == camera.id }.parentId)
    }

    @Test fun movingVirtualSubtreeUpdatesGroupWithoutInventingParentId() {
        val group2 = group.copy(id = "37010301022150000002")
        val moved = moveCatalogNode(org, group2, tree + group2)
        assertEquals("", moved.first { it.id == org.id }.parentId)
        assertEquals(group2.id, moved.first { it.id == org.id }.fields["BusinessGroupID"])
        assertEquals(group2.id, moved.first { it.id == nested.id }.fields["BusinessGroupID"])
    }
}

package com.uvp.sim.domain

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CatalogTreeValidationTest {

    private val rootId = "34020000001310000001"
    private fun root(parent: String = rootId) =
        CatalogNode(rootId, CatalogNodeType.Device, "Dev", parent)

    @Test
    fun emptyTreeIsValid() {
        assertEquals(ValidationResult.Ok, CatalogTreeStore.validate(emptyList()))
    }

    @Test
    fun validFlatTreeIsOk() {
        val tree = listOf(
            root(),
            CatalogNode("34020000001320000001", CatalogNodeType.VideoChannel, "V", rootId)
        )
        assertEquals(ValidationResult.Ok, CatalogTreeStore.validate(tree))
    }

    @Test
    fun blankIdRejected() {
        val tree = listOf(
            root(),
            CatalogNode("", CatalogNodeType.VideoChannel, "X", rootId)
        )
        val r = CatalogTreeStore.validate(tree)
        assertIs<ValidationResult.Invalid>(r)
        assertTrue(r.errors.any { "ID 不能为空" in it })
    }

    @Test
    fun duplicateIdRejected() {
        val tree = listOf(
            root(),
            CatalogNode("dup", CatalogNodeType.VideoChannel, "A", rootId),
            CatalogNode("dup", CatalogNodeType.VideoChannel, "B", rootId)
        )
        val r = CatalogTreeStore.validate(tree)
        assertIs<ValidationResult.Invalid>(r)
        assertTrue(r.errors.any { "重复" in it && "dup" in it })
    }

    @Test
    fun missingRootRejected() {
        val tree = listOf(
            CatalogNode("a", CatalogNodeType.VideoChannel, "A", "b"),
            CatalogNode("b", CatalogNodeType.BusinessGroup, "B", "a")
        )
        val r = CatalogTreeStore.validate(tree)
        assertIs<ValidationResult.Invalid>(r)
        assertTrue(r.errors.any { "缺少设备根节点" in it })
    }

    @Test
    fun multipleRootsRejected() {
        val tree = listOf(
            CatalogNode("r1", CatalogNodeType.Device, "R1", "r1"),
            CatalogNode("r2", CatalogNodeType.Device, "R2", "r2")
        )
        val r = CatalogTreeStore.validate(tree)
        assertIs<ValidationResult.Invalid>(r)
        assertTrue(r.errors.any { "只能有一个 Device 或 System 根节点" in it })
    }

    @Test
    fun rootParentNotPointingToSelfRejected() {
        val tree = listOf(
            CatalogNode(rootId, CatalogNodeType.Device, "Dev", "other"),
            CatalogNode("other", CatalogNodeType.BusinessGroup, "G", rootId)
        )
        val r = CatalogTreeStore.validate(tree)
        assertIs<ValidationResult.Invalid>(r)
        assertTrue(r.errors.any { "parentId 必须指向自身" in it })
    }

    @Test
    fun missingParentIdRejected() {
        val tree = listOf(
            root(),
            CatalogNode("v", CatalogNodeType.VideoChannel, "V", "ghost-parent")
        )
        val r = CatalogTreeStore.validate(tree)
        assertIs<ValidationResult.Invalid>(r)
        assertTrue(r.errors.any { "父节点=ghost-parent 不存在" in it })
    }

    @Test
    fun blankParentIdRejected() {
        val tree = listOf(
            root(),
            CatalogNode("v", CatalogNodeType.VideoChannel, "V", "")
        )
        val r = CatalogTreeStore.validate(tree)
        assertIs<ValidationResult.Invalid>(r)
        assertTrue(r.errors.any { "parentId 不能为空" in it })
    }

    @Test
    fun blankNameRejected() {
        val tree = listOf(
            root(),
            CatalogNode("v", CatalogNodeType.VideoChannel, "", rootId)
        )
        val r = CatalogTreeStore.validate(tree)
        assertIs<ValidationResult.Invalid>(r)
        assertTrue(r.errors.any { "名字不能为空" in it })
    }

    @Test
    fun cycleRejected() {
        // a → b → c → a 形成循环
        val tree = listOf(
            root(),
            CatalogNode("a", CatalogNodeType.BusinessGroup, "A", "c"),
            CatalogNode("b", CatalogNodeType.BusinessGroup, "B", "a"),
            CatalogNode("c", CatalogNodeType.BusinessGroup, "C", "b")
        )
        val r = CatalogTreeStore.validate(tree)
        assertIs<ValidationResult.Invalid>(r)
        assertTrue(r.errors.any { "循环" in it })
    }

    @Test
    fun complexValidTreeIsOk() {
        val systemId = "34020000002000000001"
        val businessGroupId = "34020000002150000001"
        val firstOrgId = "34020000002160000001"
        val nestedOrgId = "34020000002160000002"
        val firstChannelId = "34020000001320000001"
        val nestedChannelId = "34020000001320000002"
        val tree = listOf(
            CatalogNode(systemId, CatalogNodeType.System, "下级平台", systemId),
            CatalogNode(businessGroupId, CatalogNodeType.BusinessGroup, "校园安防", systemId),
            CatalogNode(
                firstOrgId,
                CatalogNodeType.VirtualOrg,
                "出入口",
                "",
                mapOf("BusinessGroupID" to businessGroupId),
            ),
            CatalogNode(
                nestedOrgId,
                CatalogNodeType.VirtualOrg,
                "东门",
                firstOrgId,
                mapOf("BusinessGroupID" to businessGroupId),
            ),
            CatalogNode(
                firstChannelId,
                CatalogNodeType.VideoChannel,
                "北门",
                "$systemId/$firstOrgId",
                mapOf("BusinessGroupID" to businessGroupId),
            ),
            CatalogNode(
                nestedChannelId,
                CatalogNodeType.VideoChannel,
                "东门通道",
                "$systemId/$nestedOrgId",
                mapOf("BusinessGroupID" to businessGroupId),
            ),
        )
        assertEquals(ValidationResult.Ok, CatalogTreeStore.validate(tree))
    }
}

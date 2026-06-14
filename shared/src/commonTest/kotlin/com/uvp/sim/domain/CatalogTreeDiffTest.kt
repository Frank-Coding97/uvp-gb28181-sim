package com.uvp.sim.domain

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTreeDiffTest {

    private val rootId = "34020000001310000001"
    private fun root() = CatalogNode(rootId, CatalogNodeType.Device, "Root", rootId)
    private fun video(id: String, name: String, parent: String = rootId) =
        CatalogNode(id, CatalogNodeType.VideoChannel, name, parent)

    @Test
    fun emptyToEmptyIsNoChange() {
        assertEquals(emptyList(), CatalogTreeStore.diff(emptyList(), emptyList()))
    }

    @Test
    fun sameTreesProduceNoChange() {
        val tree = listOf(root(), video("v1", "V1"))
        assertEquals(emptyList(), CatalogTreeStore.diff(tree, tree))
    }

    @Test
    fun addNodeProducesAddEvent() {
        val old = listOf(root())
        val new = listOf(root(), video("v1", "V1"))
        val events = CatalogTreeStore.diff(old, new)
        assertEquals(1, events.size)
        assertTrue(events[0] is CatalogChangeEvent.Add)
        assertEquals("v1", (events[0] as CatalogChangeEvent.Add).node.id)
    }

    @Test
    fun deleteNodeProducesDelEvent() {
        val old = listOf(root(), video("v1", "V1"))
        val new = listOf(root())
        val events = CatalogTreeStore.diff(old, new)
        assertEquals(1, events.size)
        assertTrue(events[0] is CatalogChangeEvent.Del)
        assertEquals("v1", (events[0] as CatalogChangeEvent.Del).id)
    }

    @Test
    fun renameProducesUpdateEvent() {
        val old = listOf(root(), video("v1", "原名"))
        val new = listOf(root(), video("v1", "新名"))
        val events = CatalogTreeStore.diff(old, new)
        assertEquals(1, events.size)
        assertTrue(events[0] is CatalogChangeEvent.Update)
        assertEquals("新名", (events[0] as CatalogChangeEvent.Update).node.name)
    }

    @Test
    fun fieldChangeProducesUpdateEvent() {
        val old = listOf(
            root(),
            video("v1", "V1").copy(fields = mapOf("Status" to "ON"))
        )
        val new = listOf(
            root(),
            video("v1", "V1").copy(fields = mapOf("Status" to "OFF"))
        )
        val events = CatalogTreeStore.diff(old, new)
        assertEquals(1, events.size)
        assertTrue(events[0] is CatalogChangeEvent.Update)
    }

    @Test
    fun parentChangeProducesUpdateEvent() {
        val old = listOf(root(), video("v1", "V1", parent = rootId))
        val new = listOf(root(), video("v1", "V1", parent = "g1"))
        val events = CatalogTreeStore.diff(old, new)
        assertEquals(1, events.size)
        assertTrue(events[0] is CatalogChangeEvent.Update)
    }

    @Test
    fun complexDiffProducesMultipleEvents() {
        val old = listOf(
            root(),
            video("v1", "V1"),
            video("v2", "原名"),
            video("v3", "保留")
        )
        val new = listOf(
            root(),
            // v1 删除
            video("v2", "改名"),  // 改名
            video("v3", "保留"),  // 不变
            video("v4", "新加")    // 新加
        )
        val events = CatalogTreeStore.diff(old, new)
        assertEquals(3, events.size)

        val adds = events.filterIsInstance<CatalogChangeEvent.Add>()
        val dels = events.filterIsInstance<CatalogChangeEvent.Del>()
        val ups = events.filterIsInstance<CatalogChangeEvent.Update>()
        assertEquals(1, adds.size)
        assertEquals("v4", adds[0].node.id)
        assertEquals(1, dels.size)
        assertEquals("v1", dels[0].id)
        assertEquals(1, ups.size)
        assertEquals("v2", ups[0].node.id)
    }

    @Test
    fun shouldUseIncrementalSmallChange() {
        val tree = (1..10).map { video("v$it", "V$it") } + root()
        // 改一个节点 → 1/11 变更比例 < 30% 且总数 < 大阈值
        val changed = tree.toMutableList()
        val idx = changed.indexOfFirst { it.id == "v1" }
        changed[idx] = video("v1", "改名")
        val events = CatalogTreeStore.diff(tree, changed)
        assertTrue(CatalogTreeStore.shouldUseIncremental(events, oldSize = tree.size))
    }

    @Test
    fun shouldUseFullForLargeChange() {
        val old = (1..5).map { video("v$it", "V$it") } + root()
        // 全删,新树 0 个 channel
        val new = listOf(root())
        val events = CatalogTreeStore.diff(old, new)
        // 5/6 ≈ 83% 变更 → 应该全量
        assertTrue(!CatalogTreeStore.shouldUseIncremental(events, oldSize = old.size))
    }

    @Test
    fun shouldUseFullForEmptyOldTree() {
        // 第一次推送(老树为空)→ 全量
        val new = listOf(root(), video("v1", "V1"))
        val events = CatalogTreeStore.diff(emptyList(), new)
        assertTrue(!CatalogTreeStore.shouldUseIncremental(events, oldSize = 0))
    }
}

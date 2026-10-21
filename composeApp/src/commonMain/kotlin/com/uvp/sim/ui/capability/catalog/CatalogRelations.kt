package com.uvp.sim.ui.capability.catalog

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType

// 编辑器展示父关系不等于报文 ParentID；首层216通过 BusinessGroupID 归组。
internal fun displayParent(node: CatalogNode, tree: List<CatalogNode>): String? {
    if (node.parentId == node.id) return null
    if (node.type == CatalogNodeType.VirtualOrg && node.parentId.isBlank()) {
        return node.fields["BusinessGroupID"]
    }
    val parents = node.parentId.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    return parents.firstOrNull { id -> tree.any { it.id == id && it.type == CatalogNodeType.VirtualOrg } }
        ?: parents.firstOrNull()
}

internal fun attachCatalogNode(node: CatalogNode, parent: CatalogNode, tree: List<CatalogNode>): CatalogNode {
    return when (node.type) {
        CatalogNodeType.VirtualOrg -> node.copy(
            parentId = if (parent.type == CatalogNodeType.BusinessGroup) "" else parent.id,
            fields = node.fields + ("BusinessGroupID" to
                if (parent.type == CatalogNodeType.BusinessGroup) parent.id
                else parent.fields["BusinessGroupID"].orEmpty())
        )
        CatalogNodeType.VideoChannel, CatalogNodeType.AlarmChannel -> {
            val physical = node.parentId.split('/').firstOrNull { id ->
                tree.any { it.id == id && it.type in setOf(CatalogNodeType.Device, CatalogNodeType.System) }
            } ?: tree.firstOrNull { it.type in setOf(CatalogNodeType.Device, CatalogNodeType.System) && it.parentId == it.id }?.id
            node.copy(parentId = if (parent.type == CatalogNodeType.VirtualOrg)
                listOfNotNull(physical, parent.id).distinct().joinToString("/") else parent.id,
                fields = node.fields - "BusinessGroupID")
        }
        else -> node.copy(parentId = parent.id)
    }
}

internal fun moveCatalogNode(node: CatalogNode, parent: CatalogNode, tree: List<CatalogNode>): List<CatalogNode> {
    val updated = attachCatalogNode(node, parent, tree)
    val descendants = if (node.type == CatalogNodeType.VirtualOrg) collectDescendants(node.id, tree) else emptySet()
    return tree.map {
        when {
            it.id == node.id -> updated
            it.id in descendants && it.type == CatalogNodeType.VirtualOrg ->
                it.copy(fields = it.fields + ("BusinessGroupID" to updated.fields["BusinessGroupID"].orEmpty()))
            else -> it
        }
    }
}

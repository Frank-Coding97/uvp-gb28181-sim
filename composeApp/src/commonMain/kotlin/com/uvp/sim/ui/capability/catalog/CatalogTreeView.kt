package com.uvp.sim.ui.capability.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.ui.UvpColor

private val GuideColor = Color(0xFFE5E7EB)
private const val INDENT_DP = 22

/** 树形目录可滚动列表(基于 DFS 顺序 + 深度缩进竖线)。 */
@Composable
internal fun CatalogTreeList(
    tree: List<CatalogNode>,
    onNodeClick: (String) -> Unit,
    onNodeMenu: (String) -> Unit
) {
    val ordered = remember(tree) { dfsOrder(tree) }
    val depthMap = remember(tree) { buildDepthMap(tree) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(UvpColor.Surface),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(ordered, key = { it.id }) { node ->
            val depth = depthMap[node.id] ?: 0
            NodeRow(
                node = node,
                depth = depth,
                onClick = { onNodeClick(node.id) },
                onMenu = { onNodeMenu(node.id) }
            )
        }
    }
}

@Composable
private fun NodeRow(
    node: CatalogNode,
    depth: Int,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩进竖线区域:每一层画一根淡灰色竖线
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width((6 + depth * INDENT_DP).dp)
        ) {
            for (i in 0 until depth) {
                Box(
                    modifier = Modifier
                        .padding(start = (6 + i * INDENT_DP).dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(GuideColor)
                )
            }
        }
        Icon(node.type.icon(), null, tint = node.type.color(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        androidx.compose.material3.Text(
            node.name,
            color = UvpColor.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        TypeChip(node.type)
        Spacer(Modifier.width(6.dp))
        androidx.compose.material3.Text(
            text = node.id,
            color = UvpColor.TextHint,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onMenu, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.MoreVert, "操作",
                tint = UvpColor.TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TypeChip(type: CatalogNodeType) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(type.color().copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        androidx.compose.material3.Text(
            text = type.shortLabel(),
            color = type.color(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun dfsOrder(tree: List<CatalogNode>): List<CatalogNode> {
    if (tree.isEmpty()) return emptyList()
    val ids = tree.mapTo(mutableSetOf()) { it.id }
    val byParent = tree.groupBy { it.parentId }
    val visited = mutableSetOf<String>()
    val out = mutableListOf<CatalogNode>()
    val roots = tree.filter { it.parentId == it.id || it.parentId !in ids }
    fun dfs(n: CatalogNode) {
        if (n.id in visited) return
        visited += n.id
        out += n
        byParent[n.id].orEmpty().filter { it.id != n.id }.forEach(::dfs)
    }
    roots.forEach(::dfs)
    tree.filter { it.id !in visited }.forEach { visited += it.id; out += it }
    return out
}

private fun buildDepthMap(tree: List<CatalogNode>): Map<String, Int> {
    val byId = tree.associateBy { it.id }
    val cache = mutableMapOf<String, Int>()
    fun depth(id: String, seen: Set<String> = emptySet()): Int {
        cache[id]?.let { return it }
        if (id in seen) return 0
        val n = byId[id] ?: return 0
        if (n.parentId == n.id || n.parentId !in byId) return 0
        return (depth(n.parentId, seen + id) + 1).also { cache[id] = it }
    }
    tree.forEach { depth(it.id) }
    return cache
}

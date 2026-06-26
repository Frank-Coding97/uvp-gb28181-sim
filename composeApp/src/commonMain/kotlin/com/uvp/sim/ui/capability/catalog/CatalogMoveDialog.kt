package com.uvp.sim.ui.capability.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.ui.UvpColor

/** spec §Q4 — drag/move 校验:Device 不可动,通道不可作父,自身/子树不可作目标。 */
internal fun canMove(
    dragged: CatalogNode,
    targetId: String,
    tree: List<CatalogNode>
): Boolean {
    if (dragged.type == CatalogNodeType.Device) return false
    val target = tree.firstOrNull { it.id == targetId } ?: return false
    if (target.type == CatalogNodeType.VideoChannel ||
        target.type == CatalogNodeType.AlarmChannel
    ) return false
    if (targetId == dragged.id) return false
    val descendants = collectDescendants(dragged.id, tree)
    if (targetId in descendants) return false
    return true
}

/** 递归收集 [id] 节点的所有后代 id 集合(不含自身)。 */
internal fun collectDescendants(id: String, tree: List<CatalogNode>): Set<String> {
    val children = tree.filter { it.parentId == id && it.id != id }.map { it.id }
    if (children.isEmpty()) return emptySet()
    return children.toSet() + children.flatMap { collectDescendants(it, tree) }
}

/** 移动到另一个合法父节点(候选已用 [canMove] 过滤)。 */
@Composable
internal fun MoveDialog(
    node: CatalogNode,
    tree: List<CatalogNode>,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val candidates = remember(tree, node) {
        tree.filter { canMove(node, it.id, tree) }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("移动 ${node.name}") },
        text = {
            if (candidates.isEmpty()) {
                Text("没有合法目标(只能移到 Device/137/138 节点,且不能移到自身子树)",
                    color = UvpColor.TextSecondary, fontSize = 12.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(candidates, key = { it.id }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onConfirm(c.id) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(c.type.icon(), null, tint = c.type.color(),
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(c.name, color = UvpColor.Text, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.gb28181.CatalogNotifyBuilder
import com.uvp.sim.gb28181.IdEncoder
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.UvpColor

/**
 * 目录管理详情页 — 树形可视化编辑器(M2)。
 *
 * 提供能力(对应 spec 验收项):
 *  - 展示当前树(递归缩进 + 节点类型图标)
 *  - 选中节点编辑字段(Name + 常用字段)
 *  - 新增子节点(选类型 + 自动生成 ID,IdEncoder)
 *  - 删除选中节点(连带后代),设备根不可删
 *  - 选「移到 ... 之下」按钮,把当前节点挂到目标父节点(对应拖拽,带 spec §Q4 校验)
 *  - 预览即将推送给平台的 Catalog NOTIFY XML(只读弹层)
 *  - 保存 → engine.updateCatalogTree → 自动推一次 NOTIFY(若 Catalog 订阅 active)
 *
 * 拖拽 UX 在 M2 范围内简化为按钮选择父节点;真正的 drag-and-drop M3 升级。
 */
@Composable
fun CatalogManagementScreen(
    state: AppUiState,
    actions: AppActions,
    onBack: () -> Unit
) {
    val initial = state.catalogTree
    var draft by remember(initial) { mutableStateOf(initial) }
    var selectedId by remember(initial) {
        mutableStateOf(initial.firstOrNull { it.type == CatalogNodeType.Device }?.id)
    }
    var showAdd by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    val isDirty = draft != initial

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UvpColor.Bg)
    ) {
        Toolbar(
            isDirty = isDirty,
            onBack = onBack,
            onAdd = { showAdd = true },
            onMove = { showMove = true },
            onPreview = { showPreview = true },
            onSave = {
                actions.onCatalogTreeSave(draft)
            },
            canModify = selectedId != null && draft.find { it.id == selectedId }?.type != CatalogNodeType.Device
        )

        Row(modifier = Modifier.fillMaxSize()) {
            // 左:树
            Box(modifier = Modifier.weight(1.2f)) {
                TreeList(
                    tree = draft,
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onDelete = { id ->
                        val ids = collectDescendants(id, draft) + id
                        draft = draft.filterNot { it.id in ids }
                        if (selectedId in ids) selectedId = draft.firstOrNull()?.id
                    }
                )
            }
            // 右:字段编辑
            Box(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                val sel = draft.firstOrNull { it.id == selectedId }
                if (sel != null) {
                    NodeEditor(
                        node = sel,
                        onChange = { updated ->
                            draft = draft.map { if (it.id == sel.id) updated else it }
                        }
                    )
                } else {
                    EmptyEditor()
                }
            }
        }
    }

    if (showAdd) {
        AddChildDialog(
            domain = state.config.server.domain,
            existingIds = draft.map { it.id }.toSet(),
            parentNode = draft.firstOrNull { it.id == selectedId },
            onCancel = { showAdd = false },
            onConfirm = { newNode ->
                draft = draft + newNode
                selectedId = newNode.id
                showAdd = false
            }
        )
    }

    if (showPreview) {
        val deviceId = draft.firstOrNull { it.type == CatalogNodeType.Device }?.id
            ?: state.config.device.deviceId
        PreviewDialog(
            xml = CatalogNotifyBuilder.build(deviceId, sn = 0, tree = draft),
            onDismiss = { showPreview = false }
        )
    }

    if (showMove) {
        val current = draft.firstOrNull { it.id == selectedId }
        if (current != null) {
            MoveDialog(
                node = current,
                tree = draft,
                onCancel = { showMove = false },
                onConfirm = { newParentId ->
                    draft = draft.map {
                        if (it.id == current.id) it.copy(parentId = newParentId) else it
                    }
                    showMove = false
                }
            )
        } else showMove = false
    }
}

/** spec §Q4: drag/move 校验。 */
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
    // 不能放进自己的子树
    val descendants = collectDescendants(dragged.id, tree)
    if (targetId in descendants) return false
    return true
}

internal fun collectDescendants(id: String, tree: List<CatalogNode>): Set<String> {
    val children = tree.filter { it.parentId == id && it.id != id }.map { it.id }
    if (children.isEmpty()) return emptySet()
    return children.toSet() + children.flatMap { collectDescendants(it, tree) }
}

@Composable
private fun Toolbar(
    isDirty: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onMove: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    canModify: Boolean
) {
    Surface(color = UvpColor.Surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回",
                    tint = UvpColor.Text, modifier = Modifier.size(20.dp))
            }
            Text(
                "目录管理",
                color = UvpColor.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            ToolbarBtn("新增", Icons.Outlined.Add, enabled = true, onClick = onAdd)
            ToolbarBtn("移到", Icons.Outlined.AccountTree, enabled = canModify, onClick = onMove)
            ToolbarBtn("预览", Icons.Outlined.Code, enabled = true, onClick = onPreview)
            Button(
                onClick = onSave,
                enabled = isDirty,
                colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Primary),
                modifier = Modifier.height(32.dp).padding(start = 4.dp)
            ) {
                Icon(Icons.Outlined.Save, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isDirty) "保存*" else "保存", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ToolbarBtn(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp),
            tint = if (enabled) UvpColor.Primary else UvpColor.TextHint)
        Spacer(Modifier.width(2.dp))
        Text(label, fontSize = 12.sp,
            color = if (enabled) UvpColor.Primary else UvpColor.TextHint)
    }
}

@Composable
private fun TreeList(
    tree: List<CatalogNode>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val ordered = remember(tree) { dfsOrder(tree) }
    val depthMap = remember(tree) { buildDepthMap(tree) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(UvpColor.Surface).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(ordered, key = { it.id }) { node ->
            val depth = depthMap[node.id] ?: 0
            NodeRow(
                node = node,
                depth = depth,
                isSelected = node.id == selectedId,
                canDelete = node.type != CatalogNodeType.Device,
                onSelect = { onSelect(node.id) },
                onDelete = { onDelete(node.id) }
            )
        }
    }
}

@Composable
private fun NodeRow(
    node: CatalogNode,
    depth: Int,
    isSelected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val bg = if (isSelected) UvpColor.PrimaryLight else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable { onSelect() }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width((depth * 14).dp))
        Icon(node.type.icon(), null, tint = node.type.color(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, color = UvpColor.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                node.id,
                color = UvpColor.TextHint,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Outlined.Delete, "删除",
                    tint = UvpColor.Danger, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun CatalogNodeType.icon(): ImageVector = when (this) {
    CatalogNodeType.Device -> Icons.Outlined.AccountTree
    CatalogNodeType.BusinessGroup -> Icons.Outlined.Folder
    CatalogNodeType.VirtualOrg -> Icons.Outlined.Folder
    CatalogNodeType.VideoChannel -> Icons.Outlined.Videocam
    CatalogNodeType.AlarmChannel -> Icons.Outlined.NotificationsActive
}

private fun CatalogNodeType.color(): Color = when (this) {
    CatalogNodeType.Device -> UvpColor.Primary
    CatalogNodeType.BusinessGroup -> UvpColor.Info
    CatalogNodeType.VirtualOrg -> UvpColor.Info
    CatalogNodeType.VideoChannel -> UvpColor.Success
    CatalogNodeType.AlarmChannel -> UvpColor.Warning
}

private fun CatalogNodeType.displayName(): String = when (this) {
    CatalogNodeType.Device -> "设备根"
    CatalogNodeType.BusinessGroup -> "业务分组(137)"
    CatalogNodeType.VirtualOrg -> "虚拟组织(138)"
    CatalogNodeType.VideoChannel -> "视频通道(132)"
    CatalogNodeType.AlarmChannel -> "报警通道(134)"
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

@Composable
private fun NodeEditor(node: CatalogNode, onChange: (CatalogNode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UvpColor.Surface)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = node.type.displayName(),
            color = node.type.color(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "ID: ${node.id}",
            color = UvpColor.TextHint,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        OutlinedTextField(
            value = node.name,
            onValueChange = { onChange(node.copy(name = it)) },
            label = { Text("名称", fontSize = 10.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth()
        )

        // 视频/报警通道,常用字段:Manufacturer / Model / Status
        if (node.type == CatalogNodeType.VideoChannel ||
            node.type == CatalogNodeType.AlarmChannel ||
            node.type == CatalogNodeType.Device
        ) {
            FieldRow("Manufacturer", node.fields["Manufacturer"] ?: "") {
                onChange(node.copy(fields = node.fields + ("Manufacturer" to it)))
            }
            FieldRow("Model", node.fields["Model"] ?: "") {
                onChange(node.copy(fields = node.fields + ("Model" to it)))
            }
            FieldRow("Status", node.fields["Status"] ?: "ON") {
                onChange(node.copy(fields = node.fields + ("Status" to it)))
            }
        }
        if (node.type == CatalogNodeType.VirtualOrg) {
            FieldRow("CivilCode", node.fields["CivilCode"] ?: "") {
                onChange(node.copy(fields = node.fields + ("CivilCode" to it)))
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun EmptyEditor() {
    Box(
        modifier = Modifier.fillMaxSize().background(UvpColor.Surface),
        contentAlignment = Alignment.Center
    ) {
        Text("点击节点选中后编辑", color = UvpColor.TextHint, fontSize = 11.sp)
    }
}

@Composable
private fun AddChildDialog(
    domain: String,
    existingIds: Set<String>,
    parentNode: CatalogNode?,
    onCancel: () -> Unit,
    onConfirm: (CatalogNode) -> Unit
) {
    var typeMenu by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(CatalogNodeType.VideoChannel) }
    var name by remember { mutableStateOf("新节点") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("新增子节点", fontSize = 13.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("父节点:${parentNode?.name ?: "(根节点)"}",
                    color = UvpColor.TextSecondary, fontSize = 11.sp)
                Box {
                    OutlinedTextField(
                        value = selectedType.displayName(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类型", fontSize = 10.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth().clickable { typeMenu = true }
                    )
                    DropdownMenu(typeMenu, onDismissRequest = { typeMenu = false }) {
                        // 只允许新增四种(根 Device 不可手动新增)
                        listOf(
                            CatalogNodeType.BusinessGroup,
                            CatalogNodeType.VirtualOrg,
                            CatalogNodeType.VideoChannel,
                            CatalogNodeType.AlarmChannel
                        ).forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.displayName(), fontSize = 12.sp) },
                                onClick = { selectedType = t; typeMenu = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称", fontSize = 10.sp) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val seq = nextSeq(existingIds, selectedType)
                val newId = IdEncoder.genChildId(domain, selectedType, seq)
                val parentId = parentNode?.id
                    ?: existingIds.firstOrNull()
                    ?: newId
                onConfirm(
                    CatalogNode(
                        id = newId,
                        type = selectedType,
                        name = name.ifBlank { selectedType.displayName() },
                        parentId = parentId
                    )
                )
            }) { Text("确定", color = UvpColor.Primary) }
        },
        dismissButton = { TextButton(onCancel) { Text("取消") } }
    )
}

private fun nextSeq(existing: Set<String>, type: CatalogNodeType): Int {
    val typeCode = type.typeCode
    val maxSeq = existing
        .filter { it.length == 20 && it.substring(10, 13) == typeCode }
        .mapNotNull { it.takeLast(7).toIntOrNull() }
        .maxOrNull() ?: 0
    return maxSeq + 1
}

@Composable
private fun PreviewDialog(xml: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catalog NOTIFY 预览", fontSize = 13.sp) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UvpColor.CodeBg)
                    .padding(8.dp)
            ) {
                Text(
                    text = xml,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = UvpColor.Text
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun MoveDialog(
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
        title = { Text("移动 ${node.name}", fontSize = 13.sp) },
        text = {
            if (candidates.isEmpty()) {
                Text("没有合法目标(只能移到 Device/137/138 节点,且不能移到自身子树)",
                    color = UvpColor.TextSecondary, fontSize = 11.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(candidates, key = { it.id }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(c.id) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(c.type.icon(), null, tint = c.type.color(),
                                modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(c.name, color = UvpColor.Text, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

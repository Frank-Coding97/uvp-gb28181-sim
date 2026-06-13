package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
 * 目录管理详情页(M2,A 方案 — 主屏树 + 底部 Sheet 编辑)。
 *
 * 交互:
 *  - 整屏给树,字段编辑通过底部 Sheet 弹起
 *  - 工具栏 3 个动作:新增 / 预览 / 保存
 *  - 节点行右侧 ⋮ 菜单:编辑字段、移到、删除(根节点只能编辑)
 *  - spec §Q4 校验:[canMove] 完整保留
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogManagementScreen(
    state: AppUiState,
    actions: AppActions,
    onBack: () -> Unit
) {
    val initial = state.catalogTree
    var draft by remember(initial) { mutableStateOf(initial) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var movingId by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf<String?>(null) }   // 父节点 id
    var showPreview by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    val isDirty = draft != initial
    val toast = com.uvp.sim.ui.LocalToastHost.current
    val catalogActive =
        state.subscriptions[com.uvp.sim.ui.SubscriptionKind.Catalog]?.active == true

    val tryLeave: () -> Unit = {
        if (isDirty) showLeaveConfirm = true else onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UvpColor.Bg)
    ) {
        Toolbar(
            isDirty = isDirty,
            onBack = tryLeave,
            onAddRoot = {
                val rootId = draft.firstOrNull { it.type == CatalogNodeType.Device }?.id
                showAdd = rootId
            },
            onPreview = { showPreview = true },
            onSave = {
                val error = actions.onCatalogTreeSave(draft)
                if (error != null) {
                    toast.error("保存失败:\n$error")
                } else if (catalogActive) {
                    toast.success("已保存,推送 ${draft.size} 节点到平台")
                } else {
                    toast.success("已保存(${draft.size} 节点)")
                }
            }
        )

        Box(modifier = Modifier.weight(1f)) {
            TreeList(
                tree = draft,
                onNodeClick = { editingId = it },
                onNodeMenu = { menuFor = it }
            )
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("放弃未保存的修改?") },
            text = {
                Text("当前有未保存的目录树修改,返回后会丢失。",
                    color = UvpColor.TextSecondary, fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    onBack()
                }) { Text("放弃修改", color = UvpColor.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text("继续编辑", color = UvpColor.Primary)
                }
            }
        )
    }

    // 节点菜单(每个节点右上角 ⋮)
    val menuNode = draft.firstOrNull { it.id == menuFor }
    if (menuNode != null) {
        NodeActionsSheet(
            node = menuNode,
            onDismiss = { menuFor = null },
            onEdit = { editingId = menuNode.id; menuFor = null },
            onAddChild = { showAdd = menuNode.id; menuFor = null },
            onMove = { movingId = menuNode.id; menuFor = null },
            onDelete = {
                val ids = collectDescendants(menuNode.id, draft) + menuNode.id
                draft = draft.filterNot { it.id in ids }
                menuFor = null
            }
        )
    }

    // 字段编辑 sheet
    val editingNode = draft.firstOrNull { it.id == editingId }
    if (editingNode != null) {
        NodeEditorSheet(
            node = editingNode,
            onDismiss = { editingId = null },
            onChange = { updated ->
                draft = draft.map { if (it.id == updated.id) updated else it }
            }
        )
    }

    // 新增子节点
    val addParentId = showAdd
    if (addParentId != null) {
        val parentNode = draft.firstOrNull { it.id == addParentId }
        AddChildDialog(
            domain = state.config.server.domain,
            existingIds = draft.map { it.id }.toSet(),
            parentNode = parentNode,
            onCancel = { showAdd = null },
            onConfirm = { newNode ->
                draft = draft + newNode
                showAdd = null
                editingId = newNode.id  // 添加后直接进编辑
            }
        )
    }

    // 移动节点
    val movingNode = draft.firstOrNull { it.id == movingId }
    if (movingNode != null) {
        MoveDialog(
            node = movingNode,
            tree = draft,
            onCancel = { movingId = null },
            onConfirm = { newParentId ->
                draft = draft.map {
                    if (it.id == movingNode.id) it.copy(parentId = newParentId) else it
                }
                movingId = null
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
    onAddRoot: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit
) {
    Surface(color = UvpColor.Surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ArrowBack, "返回",
                    tint = UvpColor.Text, modifier = Modifier.size(20.dp))
            }
            Text(
                "目录管理",
                color = UvpColor.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = onAddRoot, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Add, "新增子节点",
                    tint = UvpColor.Primary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Code, "预览 NOTIFY",
                    tint = UvpColor.Primary, modifier = Modifier.size(20.dp))
            }
            Button(
                onClick = onSave,
                enabled = isDirty,
                colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Primary),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp).padding(start = 4.dp)
            ) {
                Icon(Icons.Outlined.Save, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isDirty) "保存*" else "保存",
                    color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TreeList(
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

private val GuideColor = Color(0xFFE5E7EB)
private const val INDENT_DP = 22

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
        Text(
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
        Text(
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
        Text(
            text = type.shortLabel(),
            color = type.color(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun CatalogNodeType.shortLabel(): String = when (this) {
    CatalogNodeType.Device -> "设备"
    CatalogNodeType.BusinessGroup -> "分组"
    CatalogNodeType.VirtualOrg -> "区划"
    CatalogNodeType.VideoChannel -> "视频"
    CatalogNodeType.AlarmChannel -> "报警"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeActionsSheet(
    node: CatalogNode,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onAddChild: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = UvpColor.Surface
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            // 头部:节点信息
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(node.type.icon(), null, tint = node.type.color(),
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(node.name, color = UvpColor.Text,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(node.type.displayName(), color = node.type.color(), fontSize = 11.sp)
                }
            }

            ActionRow(Icons.Outlined.Edit, "编辑字段", UvpColor.Primary, onEdit)
            // 只有 Device / BusinessGroup / VirtualOrg 能加子节点
            if (node.type == CatalogNodeType.Device ||
                node.type == CatalogNodeType.BusinessGroup ||
                node.type == CatalogNodeType.VirtualOrg
            ) {
                ActionRow(Icons.Outlined.Add, "新增子节点", UvpColor.Primary, onAddChild)
            }
            // Device 不能移动
            if (node.type != CatalogNodeType.Device) {
                ActionRow(Icons.Outlined.DriveFileMove, "移到其它父节点", UvpColor.Info, onMove)
                ActionRow(Icons.Outlined.Delete, "删除(连同子节点)", UvpColor.Danger, onDelete)
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = UvpColor.Text, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeEditorSheet(
    node: CatalogNode,
    onDismiss: () -> Unit,
    onChange: (CatalogNode) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = UvpColor.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(node.type.icon(), null, tint = node.type.color(),
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(node.type.displayName(), color = node.type.color(),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "ID: ${node.id}",
                color = UvpColor.TextHint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            OutlinedTextField(
                value = node.name,
                onValueChange = { onChange(node.copy(name = it)) },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (node.type == CatalogNodeType.VideoChannel ||
                node.type == CatalogNodeType.AlarmChannel ||
                node.type == CatalogNodeType.Device
            ) {
                FieldInput("Manufacturer", node.fields["Manufacturer"] ?: "") {
                    onChange(node.copy(fields = node.fields + ("Manufacturer" to it)))
                }
                FieldInput("Model", node.fields["Model"] ?: "") {
                    onChange(node.copy(fields = node.fields + ("Model" to it)))
                }
                FieldInput("Status (ON/OFF)", node.fields["Status"] ?: "ON") {
                    onChange(node.copy(fields = node.fields + ("Status" to it)))
                }
            }
            if (node.type == CatalogNodeType.VirtualOrg) {
                FieldInput(
                    label = "CivilCode",
                    value = node.fields["CivilCode"] ?: "",
                    helper = "行政区划码 6 位,如 340200=芜湖、110000=北京"
                ) {
                    onChange(node.copy(fields = node.fields + ("CivilCode" to it)))
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("完成", color = UvpColor.Primary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun FieldInput(
    label: String,
    value: String,
    helper: String? = null,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = helper?.let {
            { Text(it, color = UvpColor.TextHint, fontSize = 10.sp) }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AddChildDialog(
    domain: String,
    existingIds: Set<String>,
    parentNode: CatalogNode?,
    onCancel: () -> Unit,
    onConfirm: (CatalogNode) -> Unit
) {
    var selectedType by remember { mutableStateOf(CatalogNodeType.VideoChannel) }
    var name by remember { mutableStateOf("新节点") }
    val typeOptions = listOf(
        CatalogNodeType.BusinessGroup,
        CatalogNodeType.VirtualOrg,
        CatalogNodeType.VideoChannel,
        CatalogNodeType.AlarmChannel
    )

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("新增子节点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("父节点:${parentNode?.name ?: "(根节点)"}",
                    color = UvpColor.TextSecondary, fontSize = 12.sp)
                Text("类型", color = UvpColor.TextSecondary, fontSize = 11.sp)
                // 4 个分段按钮平铺(2x2)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        typeOptions.take(2).forEach { t ->
                            TypeOptionButton(
                                type = t,
                                selected = selectedType == t,
                                modifier = Modifier.weight(1f),
                                onClick = { selectedType = t }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        typeOptions.drop(2).forEach { t ->
                            TypeOptionButton(
                                type = t,
                                selected = selectedType == t,
                                modifier = Modifier.weight(1f),
                                onClick = { selectedType = t }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
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

@Composable
private fun TypeOptionButton(
    type: CatalogNodeType,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tint = type.color()
    val bg = if (selected) tint.copy(alpha = 0.15f) else UvpColor.Bg
    val border = if (selected) tint else Color(0xFFE5E7EB)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = border,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(type.icon(), null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            type.displayName(),
            color = if (selected) tint else UvpColor.Text,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
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
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Catalog NOTIFY 预览", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(xml))
                    copied = true
                }) {
                    Text(if (copied) "已复制 ✓" else "复制",
                        color = if (copied) UvpColor.Success else UvpColor.Primary,
                        fontSize = 12.sp)
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .background(UvpColor.CodeBg)
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                Text(
                    text = xml,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = UvpColor.Text,
                    softWrap = false
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

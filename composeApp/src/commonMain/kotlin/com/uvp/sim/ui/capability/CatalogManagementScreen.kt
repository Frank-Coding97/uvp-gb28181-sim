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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
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
    var showResetConfirm by remember { mutableStateOf(false) }
    var showTemplate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showOnboarding by remember { mutableStateOf(true) }
    val isDirty = draft != initial
    val toast = com.uvp.sim.ui.LocalToastHost.current
    val catalogActive =
        state.subscriptions[com.uvp.sim.ui.SubscriptionKind.Catalog]?.active == true

    val tryLeave: () -> Unit = {
        if (isDirty) showLeaveConfirm = true else onBack()
    }

    com.uvp.sim.ui.PlatformBackHandler(enabled = true, onBack = tryLeave)

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
            },
            onResetDefault = { showResetConfirm = true },
            onApplyTemplate = { showTemplate = true },
            onImportJson = { showImport = true },
            onExportJson = { showExport = true },
            onToggleSearch = {
                showSearch = !showSearch
                if (!showSearch) searchQuery = ""
            }
        )

        if (showSearch) {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClose = { showSearch = false; searchQuery = "" }
            )
        }

        if (showOnboarding) {
            OnboardingHint(onDismiss = { showOnboarding = false })
        }

        Box(modifier = Modifier.weight(1f)) {
            val visibleTree = if (searchQuery.isBlank()) draft
                else filterTreeByQuery(draft, searchQuery)
            TreeList(
                tree = visibleTree,
                onNodeClick = { editingId = it },
                onNodeMenu = { menuFor = it }
            )
        }

        if (state.lastCatalogSavedAt != null) {
            Surface(color = UvpColor.Surface) {
                Text(
                    text = "上次保存:${humanizedAgo(state.lastCatalogSavedAt)}",
                    color = UvpColor.TextHint,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }

    if (showTemplate) {
        ApplyTemplateDialog(
            templates = remember(state.config) {
                com.uvp.sim.domain.CatalogTreeStore.templates(state.config)
            },
            onCancel = { showTemplate = false },
            onConfirm = { tpl ->
                draft = tpl.nodes
                showTemplate = false
                toast.success("已应用模板「${tpl.title}」(${tpl.nodes.size} 节点,待保存)")
            }
        )
    }

    if (showExport) {
        val deviceId = draft.firstOrNull { it.type == CatalogNodeType.Device }?.id
            ?: state.config.device.deviceId
        val json = exportTreeAsJson(draft)
        ExportJsonDialog(
            json = json,
            nodeCount = draft.size,
            deviceId = deviceId,
            onDismiss = { showExport = false }
        )
    }

    if (showImport) {
        ImportJsonDialog(
            onCancel = { showImport = false },
            onConfirm = { imported ->
                draft = imported
                showImport = false
                toast.success("已导入 ${imported.size} 个节点(待保存)")
            },
            onError = { msg ->
                toast.error(msg)
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("复位到默认?") },
            text = {
                Text(
                    "把当前编辑中的目录树替换为默认树(从设备配置生成的 3 节点扁平树)。" +
                    "替换后还需要点保存才会真正写入平台。",
                    color = UvpColor.TextSecondary, fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    draft = com.uvp.sim.domain.CatalogTreeStore.defaultTree(state.config)
                    toast.info("已复位为默认树,点保存生效")
                }) { Text("复位", color = UvpColor.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("取消", color = UvpColor.Primary)
                }
            }
        )
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
            onClone = {
                val cloneId = nextSeqId(state.config.server.domain, draft, menuNode.type)
                val clone = menuNode.copy(
                    id = cloneId,
                    name = "${menuNode.name}-副本",
                    parentId = menuNode.parentId
                )
                draft = draft + clone
                editingId = clone.id  // 克隆后直接进编辑
                menuFor = null
            },
            onDelete = {
                val ids = collectDescendants(menuNode.id, draft) + menuNode.id
                draft = draft.filterNot { it.id in ids }
                menuFor = null
            },
            onToggleStatus = {
                val nowOnline = menuNode.fields["Status"] != "OFF"
                actions.onToggleChannelStatus(menuNode.id, !nowOnline)
                // 本地 draft 也立即更新,UI 即时反映
                draft = draft.map {
                    if (it.id == menuNode.id) {
                        it.copy(fields = it.fields + ("Status" to if (nowOnline) "OFF" else "ON"))
                    } else it
                }
                // 反馈订阅状态:有订阅 → 已 fan-out 简化包;无订阅 → 仅本地标记
                val targetLabel = if (nowOnline) "离线" else "在线"
                if (catalogActive) {
                    toast.success("通道已切$targetLabel,简化 NOTIFY 已推送给 Catalog 订阅")
                } else {
                    toast.info("通道已切$targetLabel(本地标记);无 Catalog 订阅,未推送 NOTIFY")
                }
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
            onConfirm = { newNodes ->
                draft = draft + newNodes
                showAdd = null
                if (newNodes.size == 1) editingId = newNodes.first().id
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
    onSave: () -> Unit,
    onResetDefault: () -> Unit,
    onApplyTemplate: () -> Unit,
    onImportJson: () -> Unit,
    onExportJson: () -> Unit,
    onToggleSearch: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
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
            IconButton(onClick = onToggleSearch, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Search, "搜索",
                    tint = UvpColor.TextSecondary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onAddRoot, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Add, "新增子节点",
                    tint = UvpColor.Primary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Code, "预览 NOTIFY",
                    tint = UvpColor.Primary, modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.MoreVert, "更多",
                        tint = UvpColor.TextSecondary, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("应用模板", fontSize = 13.sp) },
                        onClick = { menuExpanded = false; onApplyTemplate() }
                    )
                    DropdownMenuItem(
                        text = { Text("导入 JSON", fontSize = 13.sp) },
                        onClick = { menuExpanded = false; onImportJson() }
                    )
                    DropdownMenuItem(
                        text = { Text("导出 JSON", fontSize = 13.sp) },
                        onClick = { menuExpanded = false; onExportJson() }
                    )
                    DropdownMenuItem(
                        text = { Text("复位到默认", fontSize = 13.sp, color = UvpColor.Danger) },
                        onClick = { menuExpanded = false; onResetDefault() }
                    )
                }
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
    onClone: () -> Unit,
    onDelete: () -> Unit,
    onToggleStatus: () -> Unit
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
            // M5 batch2 §7.10 — 通道在线状态切换(只对叶子通道有意义,
            // 目录类节点(Device/BG/VirtualOrg)平台一般不显示在/离线,排除以免误用)
            if (node.type == CatalogNodeType.VideoChannel ||
                node.type == CatalogNodeType.AlarmChannel
            ) {
                val isOnline = node.fields["Status"] != "OFF"
                ActionRow(
                    icon = if (isOnline) Icons.Outlined.WifiOff else Icons.Outlined.Wifi,
                    label = if (isOnline) "模拟离线(发简化 NOTIFY)" else "恢复在线(发简化 NOTIFY)",
                    tint = if (isOnline) UvpColor.Warning else UvpColor.Success,
                    onClick = onToggleStatus
                )
            }
            // 只有 Device / BusinessGroup / VirtualOrg 能加子节点
            if (node.type == CatalogNodeType.Device ||
                node.type == CatalogNodeType.BusinessGroup ||
                node.type == CatalogNodeType.VirtualOrg
            ) {
                ActionRow(Icons.Outlined.Add, "新增子节点", UvpColor.Primary, onAddChild)
            }
            // Device 不能克隆/移动
            if (node.type != CatalogNodeType.Device) {
                ActionRow(Icons.Outlined.ContentCopy, "克隆节点", UvpColor.Primary, onClone)
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
    onConfirm: (List<CatalogNode>) -> Unit
) {
    var selectedType by remember { mutableStateOf(CatalogNodeType.VideoChannel) }
    var name by remember { mutableStateOf("新节点") }
    var batchCountText by remember { mutableStateOf("1") }
    val typeOptions = listOf(
        CatalogNodeType.BusinessGroup,
        CatalogNodeType.VirtualOrg,
        CatalogNodeType.VideoChannel,
        CatalogNodeType.AlarmChannel
    )
    // 只有视频/报警通道支持批量,组织类型只能单个
    val supportsBatch = selectedType == CatalogNodeType.VideoChannel ||
        selectedType == CatalogNodeType.AlarmChannel
    val batchCount = batchCountText.toIntOrNull()?.coerceIn(1, 50) ?: 1

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
                    label = { Text(if (batchCount > 1) "名称(批量自动加序号)" else "名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (supportsBatch) {
                    OutlinedTextField(
                        value = batchCountText,
                        onValueChange = { v ->
                            // 限输入数字,最多 2 位
                            batchCountText = v.filter { it.isDigit() }.take(2)
                        },
                        label = { Text("数量(1-50)") },
                        supportingText = { Text("≥2 时按 'name-001' 顺序生成",
                            color = UvpColor.TextHint, fontSize = 10.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parentId = parentNode?.id
                    ?: existingIds.firstOrNull()
                    ?: ""
                val baseName = name.ifBlank { selectedType.displayName() }
                val nodes = mutableListOf<CatalogNode>()
                val ids = existingIds.toMutableSet()
                repeat(batchCount.coerceAtLeast(1)) { i ->
                    val seq = nextSeq(ids, selectedType)
                    val newId = IdEncoder.genChildId(domain, selectedType, seq)
                    ids += newId
                    val itemName = if (batchCount > 1) {
                        "$baseName-${(i + 1).toString().padStart(3, '0')}"
                    } else baseName
                    nodes += CatalogNode(
                        id = newId,
                        type = selectedType,
                        name = itemName,
                        parentId = parentId.ifBlank { newId }  // 没父节点 → 自指(根节点场景)
                    )
                }
                onConfirm(nodes)
            }) {
                Text(
                    if (batchCount > 1) "批量新增 ($batchCount)" else "确定",
                    color = UvpColor.Primary
                )
            }
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

/** 给指定 type 算下一个可用 ID(自动避开树中已有同类型节点的最大序号)。 */
internal fun nextSeqId(domain: String, tree: List<CatalogNode>, type: CatalogNodeType): String {
    val seq = nextSeq(tree.map { it.id }.toSet(), type)
    return com.uvp.sim.gb28181.IdEncoder.genChildId(domain, type, seq)
}

internal fun exportTreeAsJson(tree: List<CatalogNode>): String =
    com.uvp.sim.config.CatalogTreeJson.encode(tree)

internal fun parseTreeJson(json: String): Result<List<CatalogNode>> = runCatching {
    com.uvp.sim.config.CatalogTreeJson.decode(json)
}

@Composable
private fun ApplyTemplateDialog(
    templates: List<com.uvp.sim.domain.CatalogTemplate>,
    onCancel: () -> Unit,
    onConfirm: (com.uvp.sim.domain.CatalogTemplate) -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("应用模板", fontSize = 14.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "应用后会替换当前编辑中的树(待保存,可反悔)。",
                    color = UvpColor.TextSecondary, fontSize = 11.sp
                )
                templates.forEach { tpl ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                1.dp,
                                Color(0xFFE5E7EB),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { onConfirm(tpl) },
                        color = UvpColor.Bg
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tpl.title, color = UvpColor.Text,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f))
                                Text("${tpl.nodes.size} 节点",
                                    color = UvpColor.Primary, fontSize = 10.sp)
                            }
                            Text(tpl.description,
                                color = UvpColor.TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

@Composable
private fun ExportJsonDialog(
    json: String,
    nodeCount: Int,
    deviceId: String,
    onDismiss: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("导出 JSON · $nodeCount 节点", modifier = Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(json))
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
                    text = json,
                    fontSize = 9.sp,
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
private fun ImportJsonDialog(
    onCancel: () -> Unit,
    onConfirm: (List<CatalogNode>) -> Unit,
    onError: (String) -> Unit
) {
    var jsonText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<CatalogNode>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("导入 JSON", fontSize = 14.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = {
                        jsonText = it
                        errorMsg = null
                        preview = null
                    },
                    placeholder = { Text("粘贴 JSON 数组(对应导出格式)", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp)
                )
                if (preview != null) {
                    Text("解析成功:${preview!!.size} 个节点。点确定替换当前编辑中的树(待保存)。",
                        color = UvpColor.Success, fontSize = 11.sp)
                }
                if (errorMsg != null) {
                    Text("解析失败:$errorMsg",
                        color = UvpColor.Danger, fontSize = 11.sp)
                }
                TextButton(onClick = {
                    parseTreeJson(jsonText)
                        .onSuccess {
                            preview = it
                            errorMsg = null
                        }
                        .onFailure {
                            errorMsg = it.message ?: "未知错误"
                            preview = null
                        }
                }) {
                    Text("解析预览", color = UvpColor.Primary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = preview
                    if (p != null) {
                        onConfirm(p)
                    } else {
                        onError("请先点「解析预览」确认 JSON 合法")
                    }
                },
                enabled = preview != null
            ) {
                Text("确定", color = if (preview != null) UvpColor.Primary else UvpColor.TextHint)
            }
        },
        dismissButton = { TextButton(onCancel) { Text("取消") } }
    )
}

@Composable
private fun OnboardingHint(onDismiss: () -> Unit) {
    Surface(
        color = UvpColor.PrimaryLight,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "目录管理使用提示",
                    color = UvpColor.PrimaryDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "• 默认 3 节点从设备配置生成,可点 ⋮ 加分组、克隆、移动\n" +
                    "• 工具栏 ⋮ 菜单可应用模板 / 导入导出 JSON / 复位\n" +
                    "• 编辑后点保存生效;若 WVP 已订阅会立刻推送\n" +
                    "• 注意:WVP 后台可能扁平展示树结构,这是平台限制",
                    color = UvpColor.Text,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Close, "关闭提示",
                    tint = UvpColor.TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Surface(color = UvpColor.Surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索名字或 ID", fontSize = 12.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                modifier = Modifier.weight(1f).height(48.dp)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, "关闭搜索",
                    tint = UvpColor.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

internal fun filterTreeByQuery(tree: List<CatalogNode>, query: String): List<CatalogNode> {
    if (query.isBlank()) return tree
    val q = query.trim().lowercase()
    val byId = tree.associateBy { it.id }
    val matched = tree.filter {
        q in it.name.lowercase() || q in it.id.lowercase()
    }
    if (matched.isEmpty()) return emptyList()
    val visible = mutableSetOf<String>()
    for (node in matched) {
        var cur: CatalogNode? = node
        while (cur != null && cur.id !in visible) {
            visible += cur.id
            if (cur.parentId == cur.id) break
            cur = byId[cur.parentId] ?: break
        }
    }
    return tree.filter { it.id in visible }
}

private fun humanizedAgo(epochMs: Long): String {
    val nowMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    val diff = (nowMs - epochMs).coerceAtLeast(0L)
    val sec = diff / 1000
    return when {
        sec < 5 -> "刚刚"
        sec < 60 -> "${sec} 秒前"
        sec < 3600 -> "${sec / 60} 分钟前"
        sec < 86400 -> "${sec / 3600} 小时前"
        else -> "${sec / 86400} 天前"
    }
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

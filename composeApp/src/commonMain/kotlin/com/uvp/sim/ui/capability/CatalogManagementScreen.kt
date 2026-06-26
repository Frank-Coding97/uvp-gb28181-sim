package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.gb28181.CatalogNotifyBuilder
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.LocalToastHost
import com.uvp.sim.ui.PlatformBackHandler
import com.uvp.sim.ui.SubscriptionKind
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.capability.catalog.AddChildDialog
import com.uvp.sim.ui.capability.catalog.ApplyTemplateDialog
import com.uvp.sim.ui.capability.catalog.CatalogOnboardingHint
import com.uvp.sim.ui.capability.catalog.CatalogPreviewDialog
import com.uvp.sim.ui.capability.catalog.CatalogSearchBar
import com.uvp.sim.ui.capability.catalog.CatalogToolbar
import com.uvp.sim.ui.capability.catalog.CatalogTreeList
import com.uvp.sim.ui.capability.catalog.ExportJsonDialog
import com.uvp.sim.ui.capability.catalog.ImportJsonDialog
import com.uvp.sim.ui.capability.catalog.LeaveUnsavedConfirmDialog
import com.uvp.sim.ui.capability.catalog.MoveDialog
import com.uvp.sim.ui.capability.catalog.NodeActionsSheet
import com.uvp.sim.ui.capability.catalog.NodeEditorSheet
import com.uvp.sim.ui.capability.catalog.ResetTreeConfirmDialog
import com.uvp.sim.ui.capability.catalog.collectDescendants
import com.uvp.sim.ui.capability.catalog.exportTreeAsJson
import com.uvp.sim.ui.capability.catalog.filterTreeByQuery
import com.uvp.sim.ui.capability.catalog.humanizedAgo
import com.uvp.sim.ui.capability.catalog.nextSeqId

/**
 * 目录管理详情页(M2,A 方案 — 主屏树 + 底部 Sheet 编辑)。
 *
 * 交互:
 *  - 整屏给树,字段编辑通过底部 Sheet 弹起
 *  - 工具栏 3 个动作:新增 / 预览 / 保存
 *  - 节点行右侧 ⋮ 菜单:编辑字段、移到、删除(根节点只能编辑)
 *  - spec §Q4 校验:[canMove] 完整保留(在 catalog/CatalogMoveDialog.kt)
 *
 * 子组件归类到 `capability/catalog/` 子包(PR-C-2 拆分,2026-06-26):
 *  - [CatalogToolbar] 顶部工具栏
 *  - [CatalogTreeList] 树视图
 *  - [CatalogSearchBar] / [CatalogOnboardingHint] 搜索条 + 引导
 *  - [NodeActionsSheet] / [NodeEditorSheet] 节点 ⋮ 菜单 + 编辑
 *  - [AddChildDialog] / [MoveDialog] / [CatalogPreviewDialog] 三个核心 dialog
 *  - [ApplyTemplateDialog] / [ExportJsonDialog] / [ImportJsonDialog] 模板 + JSON
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
    val toast = LocalToastHost.current
    val catalogActive =
        state.subscriptions[SubscriptionKind.Catalog]?.active == true

    val tryLeave: () -> Unit = {
        if (isDirty) showLeaveConfirm = true else onBack()
    }

    PlatformBackHandler(enabled = true, onBack = tryLeave)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UvpColor.Bg)
    ) {
        CatalogToolbar(
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
            CatalogSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClose = { showSearch = false; searchQuery = "" }
            )
        }

        if (showOnboarding) {
            CatalogOnboardingHint(onDismiss = { showOnboarding = false })
        }

        Box(modifier = Modifier.weight(1f)) {
            val visibleTree = if (searchQuery.isBlank()) draft
                else filterTreeByQuery(draft, searchQuery)
            CatalogTreeList(
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
        ResetTreeConfirmDialog(
            onCancel = { showResetConfirm = false },
            onConfirm = {
                showResetConfirm = false
                draft = com.uvp.sim.domain.CatalogTreeStore.defaultTree(state.config)
                toast.info("已复位为默认树,点保存生效")
            }
        )
    }

    if (showLeaveConfirm) {
        LeaveUnsavedConfirmDialog(
            onCancel = { showLeaveConfirm = false },
            onConfirm = {
                showLeaveConfirm = false
                onBack()
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
        CatalogPreviewDialog(
            xml = CatalogNotifyBuilder.build(deviceId, sn = 0, tree = draft),
            onDismiss = { showPreview = false }
        )
    }
}

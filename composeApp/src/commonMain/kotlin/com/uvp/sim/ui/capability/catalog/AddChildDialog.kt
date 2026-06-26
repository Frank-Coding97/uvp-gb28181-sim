package com.uvp.sim.ui.capability.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.gb28181.IdEncoder
import com.uvp.sim.ui.UvpColor

/**
 * 新增子节点 dialog。
 *
 * 业务规则:
 *  - 类型 4 选 1(分组/区划/视频/报警)
 *  - 视频/报警支持批量(1-50),按 `name-001` 顺序生成
 *  - ID 通过 [nextSeqId] 自增,避开树中已有同类型最大序号
 *  - 无父节点(根节点场景)时 parentId 自指到新 id
 */
@Composable
internal fun AddChildDialog(
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
    return IdEncoder.genChildId(domain, type, seq)
}

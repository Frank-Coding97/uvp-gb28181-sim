package com.uvp.sim.ui.capability.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.ui.UvpColor

/**
 * 节点 ⋮ 操作菜单(ModalBottomSheet)。
 *
 * 业务规则:
 *  - 视频/报警通道额外有「在线状态切换」入口(切换会发简化 NOTIFY)
 *  - Device/BG/VirtualOrg 才能加子节点
 *  - Device 不能克隆 / 移动 / 删除
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeActionsSheet(
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

/** 节点字段编辑 sheet(底部弹起,改名 + Manufacturer/Model/Status/CivilCode)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeEditorSheet(
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

package com.uvp.sim.ui.capability.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor

/** 目录管理顶部工具栏(返回 / 标题 / 搜索 / 新增 / 预览 / 更多菜单 / 保存)。 */
@Composable
internal fun CatalogToolbar(
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

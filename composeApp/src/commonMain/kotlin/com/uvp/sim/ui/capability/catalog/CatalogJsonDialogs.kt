package com.uvp.sim.ui.capability.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogTreeJson
import com.uvp.sim.domain.CatalogTemplate
import com.uvp.sim.ui.UvpColor

internal fun exportTreeAsJson(tree: List<CatalogNode>): String =
    CatalogTreeJson.encode(tree)

internal fun parseTreeJson(json: String): Result<List<CatalogNode>> = runCatching {
    CatalogTreeJson.decode(json)
}

/** 应用预置模板(替换当前编辑中的树,待保存)。 */
@Composable
internal fun ApplyTemplateDialog(
    templates: List<CatalogTemplate>,
    onCancel: () -> Unit,
    onConfirm: (CatalogTemplate) -> Unit
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

/** 导出当前编辑中的树为 JSON(可复制到剪贴板)。 */
@Composable
internal fun ExportJsonDialog(
    json: String,
    nodeCount: Int,
    deviceId: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("导出 JSON · $nodeCount 节点", modifier = Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(json))
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

/** 从粘贴的 JSON 导入树(必须先点解析预览,确认合法才能确定)。 */
@Composable
internal fun ImportJsonDialog(
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
                    textStyle = TextStyle(
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

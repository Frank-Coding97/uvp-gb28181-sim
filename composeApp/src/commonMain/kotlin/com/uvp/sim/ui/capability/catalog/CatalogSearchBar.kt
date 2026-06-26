package com.uvp.sim.ui.capability.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.ui.UvpColor

/** 顶部使用提示条(老板可点 ✕ 关闭,会话内不再展示)。 */
@Composable
internal fun CatalogOnboardingHint(onDismiss: () -> Unit) {
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

/** 名字/ID 搜索条(打开/关闭由主屏控制)。 */
@Composable
internal fun CatalogSearchBar(
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
                textStyle = TextStyle(fontSize = 13.sp),
                modifier = Modifier.weight(1f).height(48.dp)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, "关闭搜索",
                    tint = UvpColor.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * 名字/ID 模糊过滤,保留匹配节点 + 全部祖先链(否则树会断)。
 * 空 query 直接返回原树。
 */
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

/** 把毫秒时间戳格式化成「刚刚 / 5 秒前 / 12 分钟前 / 3 小时前 / 4 天前」。 */
internal fun humanizedAgo(epochMs: Long): String {
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

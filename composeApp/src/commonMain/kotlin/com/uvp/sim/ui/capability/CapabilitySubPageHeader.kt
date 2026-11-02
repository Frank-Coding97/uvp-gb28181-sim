package com.uvp.sim.ui.capability

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor

/**
 * 能力 Tab 各子页的统一顶部栏：白底条 + 返回键 + 标题（+ 可选副标 / 右侧动作）。
 *
 * 起因是五个子页各写各的：目录管理 / 报警 / 录像列表是「[UvpColor.Surface] 底 +
 * 36dp 返回键 + 16sp 标题」，录像计划 / 设备校时是「无底色 + 48dp 默认返回键 + 17sp 标题」，
 * 同一层级切来切去时返回键会跳一下大小、标题会跳一下字号。收敛到这一处，
 * 新增子页直接复用，不用再抄一遍数值。
 *
 * 尺寸口径以目录管理为准（它是这套子页里动作最多、最经得起挤的那个）：
 *  - 横向 6dp / 纵向 8dp 内边距，整条高 52dp
 *  - 返回键 36dp 命中区 + 20dp 图标
 *  - 标题 16sp SemiBold，紧跟返回键 4dp
 *
 * 返回图标用 AutoMirrored 版本：RTL 语言下自动镜像成「向右返回」。
 *
 * @param title 页面标题。
 * @param onBack 返回回调；调用方自己决定是直接退还是先弹「未保存」确认。
 * @param meta 标题右侧的弱化副标（如 `A.2.1.15`），传 null 不占位。
 * @param trailing 右侧动作区（搜索 / 新增 / 保存等），从右往左排。
 */
@Composable
internal fun CapabilitySubPageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = UvpColor.Surface, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = UvpColor.Text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                title,
                color = UvpColor.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
            if (meta != null) {
                Spacer(Modifier.width(8.dp))
                Text(meta, color = UvpColor.TextHint, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

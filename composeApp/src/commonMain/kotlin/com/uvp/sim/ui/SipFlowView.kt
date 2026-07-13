package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.DialogRowDto
import com.uvp.sim.ui.model.FlowItemDto

/**
 * sngrep 风格时序图(P1 重设计版)。
 *
 * 设计原则:
 * - 运维视角:Dialog 头显示业务名(注册 / 视频点播)而非 hex Call-ID
 * - 单列时间轴:替代之前的双列 sim/平台 lane(信息密度更高)
 * - 信号克制:主色 = 灰阶 + 蓝(发出)/ 绿(收到)/ 橙(媒体)
 * - 时间戳:HH:mm:ss 主时间(秒级即可识别),毫秒在小副字
 * - 折叠默认:Dialog 默认展开,心跳簇默认折叠
 *
 * 拆分:行渲染在 [SipFlowRows](MessageLine/MediaLine/ClusterChip),
 * 辅助函数在 [SipFlowHelpers](dialogBusinessTitle / formatHmsFlow / formatDialogForCopy)。
 */
@Composable
fun SipFlowView(items: List<FlowItemDto>) {
    val collapsed = remember { mutableStateOf(setOf<String>()) }
    val expandedClusters = remember { mutableStateOf(setOf<Long>()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 悬浮 tab bar 底部预留(iOS 130dp / 其他 0dp) —— 让最后一条能滚出 tab bar
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 8.dp,
            bottom = 8.dp + floatingBottomBarReservedBottom
        )
    ) {
        itemsIndexed(items) { _, item ->
            when (item) {
                is FlowItemDto.Dialog -> DialogCard(
                    item,
                    isCollapsed = item.callId in collapsed.value,
                    onToggle = {
                        collapsed.value = if (item.callId in collapsed.value)
                            collapsed.value - item.callId
                        else collapsed.value + item.callId
                    }
                )
                is FlowItemDto.HeartbeatCluster -> ClusterChip(
                    item,
                    expanded = item.firstAtMs in expandedClusters.value,
                    onToggle = {
                        expandedClusters.value =
                            if (item.firstAtMs in expandedClusters.value)
                                expandedClusters.value - item.firstAtMs
                            else expandedClusters.value + item.firstAtMs
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/* ─────────────────────────  Dialog 卡片  ───────────────────────── */

@Composable
private fun DialogCard(
    dialog: FlowItemDto.Dialog,
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    val title = dialogBusinessTitle(dialog)
    val timeRange = "${formatHmsFlow(dialog.startedAtMs)} - ${formatHmsFlow(dialog.rows.lastTimestamp() ?: dialog.startedAtMs)}"
    val clipboard = LocalClipboardManager.current
    val toast = LocalToastHost.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
    ) {
        // ── Dialog 头 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isCollapsed) "▸" else "▾",
                fontSize = 12.sp,
                color = UvpColor.TextSecondary,
                modifier = Modifier.width(14.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 13.sp,
                    color = UvpColor.Text,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "$timeRange · ${dialog.rows.size} 条",
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .background(UvpColor.Bg, RoundedCornerShape(3.dp))
                    .border(1.dp, UvpColor.Border, RoundedCornerShape(3.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(formatDialogForCopy(dialog, title)))
                        toast.success("会话已复制到剪贴板")
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制本次会话",
                        modifier = Modifier.size(11.dp),
                        tint = UvpColor.TextSecondary
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "复制",
                        fontSize = 9.sp,
                        color = UvpColor.TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        if (!isCollapsed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(UvpColor.Border)
            )
            // 双列 lane 头
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    "sim",
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "平台",
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                dialog.rows.forEach { row ->
                    when (row) {
                        is DialogRowDto.Message -> MessageLine(row)
                        is DialogRowDto.MediaSegment -> MediaLine(row)
                    }
                }
            }
        }
    }
}

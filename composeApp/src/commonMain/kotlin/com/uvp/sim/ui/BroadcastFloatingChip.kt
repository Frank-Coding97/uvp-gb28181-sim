package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * M3 语音对讲浮动方块 —— 挂在 App 根 Box,浮在所有 Tab 之上,可拖拽。
 *
 * 设计:贴右侧边垂直居中初始停靠(悬浮球习惯位,避开状态栏);竖向方块卡片;
 * 蓝色渐变 + 半透明 + 高光描边 + 投影。内容自上而下:📢 / 对讲中 / 来源末四位 /
 * 分隔线 / 🔊·✕。点上半区弹统计 sheet。
 *
 * 作为 [BoxScope] 扩展以便用 [BoxScope.align] 定位。
 */
@Composable
fun BoxScope.BroadcastFloatingChip(state: AppUiState, actions: AppActions) {
    val bc = state.broadcast
    if (!bc.isReceiving) return

    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var showSheet by remember { mutableStateOf(false) }

    // 蓝色系渐变 + ~90% 不透明(留一点透明感)
    val cardBrush = Brush.verticalGradient(
        listOf(Color(0xE63B82F6), Color(0xE61D4ED8))
    )

    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 8.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .shadow(10.dp, RoundedCornerShape(18.dp))
            .background(cardBrush, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x59FFFFFF), RoundedCornerShape(18.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offsetX += drag.x
                    offsetY += drag.y
                }
            }
            .widthIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 上半区:图标 + 标题 + 末四位(点击弹统计)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { showSheet = true }
        ) {
            Text("📢", fontSize = 19.sp)
            Spacer(Modifier.height(3.dp))
            Text("对讲中", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(
                bc.sourceId?.takeLast(4) ?: "----",
                fontSize = 10.sp, color = Color(0xCCFFFFFF), fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(9.dp))
        // 细分隔线
        Box(Modifier.width(38.dp).height(1.dp).background(Color(0x33FFFFFF)))
        Spacer(Modifier.height(9.dp))

        // 操作行:静音切换 + 停止
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                if (bc.speakerOn) "🔊" else "🔇", fontSize = 16.sp,
                modifier = Modifier.size(22.dp).clickable { actions.onBroadcastToggleSpeaker(!bc.speakerOn) }
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "✕", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.size(22.dp).clickable { actions.onBroadcastStop() }
            )
        }
    }

    if (showSheet) {
        BroadcastStatsSheet(bc) { showSheet = false }
    }
}

@Composable
private fun BroadcastStatsSheet(bc: BroadcastState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("语音对讲") },
        text = {
            Column {
                Text("来源: ${bc.sourceId ?: "-"}", fontSize = 12.sp)
                Text("编码: ${bc.codec ?: "-"}", fontSize = 12.sp)
                Text("扬声器: ${if (bc.speakerOn) "开" else "静音"}", fontSize = 12.sp)
                Text("本地端口: ${bc.localAudioPort}", fontSize = 12.sp)
                Text("远端: ${bc.remoteAudioHost ?: "-"}:${bc.remoteAudioPort}", fontSize = 12.sp)
                Text("接收包数: ${bc.rxPackets}", fontSize = 12.sp)
                Text("接收字节: ${bc.rxBytes}", fontSize = 12.sp)
                Text("丢包(seq): ${bc.seqLost}", fontSize = 12.sp)
                Text("解码错误: ${bc.decodeErrors}", fontSize = 12.sp)
            }
        }
    )
}

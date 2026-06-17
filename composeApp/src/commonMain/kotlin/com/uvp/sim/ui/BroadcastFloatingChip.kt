package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * M3 语音对讲浮动方块 —— 挂在 App 根 Box,浮在所有 Tab 之上,可拖拽到任意位置。
 *
 * 仅当 `broadcast.isReceiving` 时显示。内容:📢 来源末四位 · 🔊/🔇 静音切换 · ✕ 停止;
 * 点文字区弹统计 sheet。初始停靠右上,拖拽累加 offset(不做边界限制,MVP)。
 *
 * 作为 [BoxScope] 扩展,以便用 [BoxScope.align] 定位。
 */
@Composable
fun BoxScope.BroadcastFloatingChip(state: AppUiState, actions: AppActions) {
    val bc = state.broadcast
    if (!bc.isReceiving) return

    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var showSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 8.dp, end = 8.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .shadow(6.dp, RoundedCornerShape(20.dp))
            .background(Color(0xFFFFF3E0), RoundedCornerShape(20.dp))
            .border(1.dp, Color(0xFFFF9800), RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offsetX += drag.x
                    offsetY += drag.y
                }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📢", fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            "对讲 ${bc.sourceId?.takeLast(4) ?: "----"}",
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE65100),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { showSheet = true }
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (bc.speakerOn) "🔊" else "🔇", fontSize = 14.sp,
            modifier = Modifier.clickable { actions.onBroadcastToggleSpeaker(!bc.speakerOn) }
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "✕", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100),
            modifier = Modifier.clickable { actions.onBroadcastStop() }
        )
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

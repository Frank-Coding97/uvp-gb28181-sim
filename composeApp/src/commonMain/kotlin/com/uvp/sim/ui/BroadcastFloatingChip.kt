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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
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
 * M3 语音对讲浮动方块 —— 玻璃拟态(glassmorphism)风格,透明科技蓝。
 *
 * 设计语言:
 *  - 正方形 squircle(82dp,圆角 24dp),贴右侧边垂直居中初始停靠
 *  - 半透明科技蓝对角渐变(cyan→blue,~60% 不透明),底层内容隐约透出 → 科技玻璃感
 *  - 顶部高光反射层 + 高光描边 + 蓝色辉光投影,模拟玻璃质感(非真 backdrop blur)
 *  - 内容自上而下:📢 / 对讲中 / 末四位 / 🔊·✕;点上半区弹统计 sheet;整体可拖拽
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

    val shape = RoundedCornerShape(24.dp)
    // 科技蓝对角渐变,半透明(留通透感)
    val glassBrush = Brush.linearGradient(
        listOf(Color(0x9922D3EE), Color(0xAA2563EB))  // cyan-400 → blue-600
    )

    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 10.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(82.dp)
            .shadow(16.dp, shape, spotColor = Color(0xFF1E66E0), ambientColor = Color(0xFF1E66E0))
            .clip(shape)
            .background(glassBrush)
            .border(1.dp, Color(0x80FFFFFF), shape)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offsetX += drag.x
                    offsetY += drag.y
                }
            }
    ) {
        // 顶部高光反射层(玻璃质感)
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0x3DFFFFFF), Color(0x00FFFFFF))))
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 上半区:图标 + 标题 + 末四位(点击弹统计)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { showSheet = true }
            ) {
                Text("📢", fontSize = 18.sp)
                Spacer(Modifier.height(2.dp))
                Text("对讲中", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    bc.sourceId?.takeLast(4) ?: "----",
                    fontSize = 9.sp, color = Color(0xCCFFFFFF), fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(36.dp).height(1.dp).background(Color(0x33FFFFFF)))
            Spacer(Modifier.height(6.dp))

            // 操作行:静音切换 + 停止
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (bc.speakerOn) "🔊" else "🔇", fontSize = 15.sp,
                    modifier = Modifier.size(20.dp).clickable { actions.onBroadcastToggleSpeaker(!bc.speakerOn) }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "✕", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.size(20.dp).clickable { actions.onBroadcastStop() }
                )
            }
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

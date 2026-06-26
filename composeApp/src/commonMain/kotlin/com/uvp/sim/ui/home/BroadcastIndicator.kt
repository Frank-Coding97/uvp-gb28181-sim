package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * M3 语音广播下行指示 chip — 显示对讲来源 / 编码 / 包数,带扬声器开关与 ✕ 关闭按钮。
 * 点 chip 可弹底片看详细 RTP 统计。从 HomeScreen.kt 拆出。
 */
@Composable
internal fun BroadcastIndicator(state: AppUiState, actions: AppActions) {
    val bc = state.broadcast
    if (!bc.isReceiving) return
    var showSheet by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x1AFF9800), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFFF9800), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📢", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "对讲中 ← ${bc.sourceId?.takeLast(4) ?: "----"}",
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE65100),
            modifier = Modifier.weight(1f).clickable { showSheet = true }
        )
        Text(
            "${bc.codec ?: "PCMA"} · ${bc.rxPackets}pkt",
            fontSize = 11.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace, maxLines = 1
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (bc.speakerOn) "🔊" else "🔇", fontSize = 15.sp,
            modifier = Modifier.clickable { actions.onBroadcastToggleSpeaker(!bc.speakerOn) }
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "✕", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100),
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

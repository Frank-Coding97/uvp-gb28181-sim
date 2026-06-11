package com.uvp.sim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.uvp.sim.sip.SipState

/**
 * 通道 Channel screen — M1 单通道展示。
 * 显示当前 deviceId + videoChannelId + 在线状态。
 * M2 才做多通道 CRUD / 录像源等。
 */
@Composable
fun ChannelScreen(state: AppUiState) {
    val online = state.sip == SipState.Registered || state.sip == SipState.InCall
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "真实摄像头 · 1",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "手机摄像头",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        state.config.device.videoChannelId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "数据通道:" + state.config.device.deviceId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusDot(online)
            }
        }
        Text(
            if (online) "通道已注册到上级平台 · 等待平台点播或主动业务"
            else "尚未注册 · 请到主页点 Connect",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    val color = if (online) UvpStatusColors.Registered else UvpStatusColors.Disconnected
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(color)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            if (online) "在线" else "离线",
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

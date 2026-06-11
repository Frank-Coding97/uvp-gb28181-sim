package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uvp.sim.sip.SipState

/**
 * 主屏 Home screen — 状态总览 + 4 主动业务快捷按钮 + Connect/Disconnect。
 * 对应 spec v1 §4.1 屏 ② 已注册主屏 (简化版,合并未注册首屏)。
 */
@Composable
fun HomeScreen(state: AppUiState, actions: AppActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusBanner(state.sip)
        ServerInfoCard(serverLabel = serverLabel(state), deviceLabel = deviceLabel(state))
        QuickActionsRow(state.sip, actions)
        ConnectRow(state.sip, actions)
        Text(
            "最近事件",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            EventListMini(state.events.take(20))
        }
    }
}

@Composable
private fun StatusBanner(state: SipState) {
    val color = state.toUvpColor()
    val label = state.toLabel()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(color)
            )
            Spacer(Modifier.size(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}

@Composable
private fun ServerInfoCard(serverLabel: String, deviceLabel: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            LabeledRow("Server", serverLabel)
            Spacer(Modifier.size(8.dp))
            LabeledRow("Device ID", deviceLabel)
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun QuickActionsRow(state: SipState, actions: AppActions) {
    val canFire = state == SipState.Registered || state == SipState.InCall
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionButton(label = "抓拍", enabled = canFire,
            onClick = actions::onSnapshot, modifier = Modifier.fillMaxWidth(0.25f))
        QuickActionButton(label = "报警", enabled = false,
            onClick = {}, modifier = Modifier.fillMaxWidth(0.33f), trailing = "M2")
        QuickActionButton(label = "位置", enabled = false,
            onClick = {}, modifier = Modifier.fillMaxWidth(0.5f), trailing = "M2")
        QuickActionButton(label = "录像", enabled = false,
            onClick = {}, modifier = Modifier.fillMaxWidth(), trailing = "M2")
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String = ""
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (trailing.isNotEmpty()) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConnectRow(state: SipState, actions: AppActions) {
    val canConnect = state == SipState.Disconnected || state == SipState.Failed
    val canDisconnect = state == SipState.Registered || state == SipState.Registering ||
        state == SipState.InCall
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = actions::onConnect,
            enabled = canConnect,
            modifier = Modifier.fillMaxWidth(0.5f),
            shape = RoundedCornerShape(10.dp)
        ) { Text("Connect") }
        OutlinedButton(
            onClick = actions::onDisconnect,
            enabled = canDisconnect,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors()
        ) { Text("Disconnect") }
    }
}

@Composable
private fun EventListMini(events: List<com.uvp.sim.domain.SimEvent>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(events) { ev ->
            val (label, detail) = renderSimEvent(ev)
            Text(
                if (detail.isNotEmpty()) "$label  $detail" else label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun SipState.toUvpColor(): Color = when (this) {
    SipState.Disconnected -> UvpStatusColors.Disconnected
    SipState.Registering -> UvpStatusColors.Registering
    SipState.Registered -> UvpStatusColors.Registered
    SipState.InCall -> UvpStatusColors.InCall
    SipState.Failed -> UvpStatusColors.Failed
}

private fun SipState.toLabel(): String = when (this) {
    SipState.Disconnected -> "Disconnected (未连接)"
    SipState.Registering -> "Registering (注册中)"
    SipState.Registered -> "Registered (已注册)"
    SipState.InCall -> "InCall (推流中)"
    SipState.Failed -> "Failed (失败)"
}

internal fun serverLabel(state: AppUiState): String =
    "${state.config.server.ip}:${state.config.server.port}  (${state.config.server.serverId})"

internal fun deviceLabel(state: AppUiState): String = state.config.device.deviceId

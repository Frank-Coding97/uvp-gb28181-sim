package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.SubscriptionKind
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.simulate.SimulateScreen

/**
 * 「能力」Tab 主屏 — 卡片化展示已实现的国标扩展能力。
 *
 * 当前卡片:
 *  - 目录管理(§9.3.1)
 *  - 模拟控制(§4)
 *  - 报警订阅(§9.4)
 */
@Composable
fun CapabilityScreen(
    state: AppUiState,
    actions: AppActions,
    openAlarmTarget: Boolean = false,
    onAlarmTargetConsumed: () -> Unit = {}
) {
    var showCatalog by remember { mutableStateOf(false) }
    var showRecording by remember { mutableStateOf(false) }
    var showAlarm by remember { mutableStateOf(false) }
    var showClockSync by remember { mutableStateOf(false) }

    // 主屏长按报警 tile 携带 target → 自动打开报警子页
    androidx.compose.runtime.LaunchedEffect(openAlarmTarget) {
        if (openAlarmTarget) {
            showAlarm = true
            onAlarmTargetConsumed()
        }
    }

    if (showCatalog) {
        CatalogManagementScreen(
            state = state,
            actions = actions,
            onBack = { showCatalog = false }
        )
        return
    }
    if (showRecording) {
        RecordingSubScreen(state = state, actions = actions, onBack = { showRecording = false })
        return
    }
    if (showAlarm) {
        AlarmManagementScreen(state = state, actions = actions, onBack = { showAlarm = false })
        return
    }
    if (showClockSync) {
        ClockSyncScreen(state = state, onBack = { showClockSync = false })
        return
    }

    val catalogSub = state.subscriptions[SubscriptionKind.Catalog]
    val catalogActive = catalogSub?.active == true
    val alarmSub = state.subscriptions[SubscriptionKind.Alarm]
    val isAlarming = state.deviceControl.isAlarming

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UvpColor.Bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityTile(
                icon = Icons.Outlined.AccountTree,
                title = "目录管理",
                metric = "${state.catalogTree.size} 节点",
                status = if (catalogActive) {
                    TileStatus.Active(
                        text = buildString {
                            append("已订阅")
                            catalogSub?.remainingSeconds?.let { append(" · 剩 ${it}s") }
                        }
                    )
                } else {
                    TileStatus.Idle("未订阅")
                },
                onClick = { showCatalog = true },
                modifier = Modifier.weight(1f)
            )
            CapabilityTile(
                icon = Icons.Outlined.Movie,
                title = "录像列表",
                metric = "${state.recording.files.size} 文件",
                status = if (state.recording.isRecording) {
                    TileStatus.Active("录像中")
                } else {
                    TileStatus.Idle("待机")
                },
                onClick = { showRecording = true },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityTile(
                icon = Icons.Outlined.NotificationsActive,
                title = "报警",
                metric = "报警事件",
                status = when {
                    isAlarming -> TileStatus.Warning("报警中")
                    alarmSub?.active == true -> TileStatus.Active(
                        buildString {
                            append("已订阅")
                            alarmSub.remainingSeconds?.let { append(" · ${it}s") }
                            append(" · ${alarmSub.notifyCount} 推")
                        }
                    )
                    else -> TileStatus.Idle("未订阅 · ${state.alarmHistory.size} 次")
                },
                onClick = { showAlarm = true },
                modifier = Modifier.weight(1f)
            )
            CapabilityTile(
                icon = Icons.Outlined.Schedule,
                title = "设备校时",
                metric = if (state.clockOffset.isSynced) {
                    formatOffsetMs(state.clockOffset.localOffsetMs() ?: 0L)
                } else "未校时",
                status = if (state.clockOffset.isSynced) {
                    TileStatus.Active("已校时")
                } else {
                    TileStatus.Idle("等注册")
                },
                onClick = { showClockSync = true },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private sealed interface TileStatus {
    val text: String
    data class Active(override val text: String) : TileStatus
    data class Idle(override val text: String) : TileStatus
    data class Warning(override val text: String) : TileStatus
}

@Composable
private fun CapabilityTile(
    icon: ImageVector,
    title: String,
    metric: String,
    status: TileStatus,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val enabled = onClick != null
    val contentAlpha = if (enabled) 1f else 0.55f

    val (dotColor, statusTextColor) = when (status) {
        is TileStatus.Active -> UvpColor.Success to UvpColor.Success
        is TileStatus.Idle -> UvpColor.TextHint to UvpColor.TextSecondary
        is TileStatus.Warning -> UvpColor.Warning to UvpColor.Warning
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .let { if (enabled) it.clickable(onClick = onClick!!) else it },
        color = UvpColor.Surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        UvpColor.Primary.copy(alpha = if (enabled) 0.12f else 0.06f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = UvpColor.Primary.copy(alpha = contentAlpha),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                color = UvpColor.Text.copy(alpha = contentAlpha),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                metric,
                color = UvpColor.Text.copy(alpha = contentAlpha * 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    status.text,
                    color = statusTextColor,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun RecordingSubScreen(state: AppUiState, actions: AppActions, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UvpColor.Bg)
    ) {
        Surface(color = UvpColor.Surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = UvpColor.Text,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    "录像列表",
                    color = UvpColor.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        com.uvp.sim.ui.RecordingScreen(state = state, actions = actions, modifier = Modifier.weight(1f))
    }
}

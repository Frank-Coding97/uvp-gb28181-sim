package com.uvp.sim.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.SipStateDto

/**
 * 主屏 4 tile 主动业务区 — 录像 / 报警 / 位置订阅 / 目录订阅。
 * 复位报警时弹确认框。点订阅 tile 弹底片看详情。从 HomeScreen.kt 拆出。
 */
@Composable
internal fun ActionButtons(state: AppUiState, actions: AppActions, onFeedback: (String) -> Unit) {
    val canFire = state.sip == SipStateDto.Registered || state.sip == SipStateDto.InCall
    val toast = LocalToastHost.current
    val navigator = LocalAppNavigator.current
    var detailFor by remember { mutableStateOf<SubscriptionKind?>(null) }
    var showAlarmResetConfirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val isRecording = state.recording.isRecording
        ActionTile(
            icon = Icons.Outlined.Videocam,
            label = if (isRecording) "停止录像" else "录像",
            enabled = canFire || isRecording,
            modifier = Modifier.weight(1f),
            recordingActive = isRecording,
            onClick = {
                if (isRecording) {
                    actions.onRecordingStop()
                    toast.info("已停止录像")
                } else {
                    actions.onRecordingStart()
                    toast.info("开始录像")
                }
            }
        )
        val isAlarming = state.deviceControl.isAlarming
        val alarmSubscribed = state.subscriptions[SubscriptionKind.Alarm]?.active == true
        ActionTile(
            icon = Icons.Outlined.Warning,
            label = "报警",
            enabled = canFire || isAlarming,
            modifier = Modifier.weight(1f),
            alarmActive = isAlarming,
            subscribed = alarmSubscribed,
            onClick = {
                if (isAlarming) {
                    showAlarmResetConfirm = true
                } else {
                    actions.onAlarmFireDefault()
                    toast.info("已发送报警")
                }
            },
            onLongClick = { navigator.navigateToAlarm() }
        )
        SubscriptionTile(
            icon = Icons.Outlined.LocationOn,
            label = "位置订阅",
            status = state.subscriptions[SubscriptionKind.MobilePosition] ?: SubscriptionStatus(),
            modifier = Modifier.weight(1f),
            onClick = { detailFor = SubscriptionKind.MobilePosition }
        )
        SubscriptionTile(
            icon = Icons.Outlined.FolderOpen,
            label = "目录订阅",
            status = state.subscriptions[SubscriptionKind.Catalog] ?: SubscriptionStatus(),
            modifier = Modifier.weight(1f),
            onClick = { detailFor = SubscriptionKind.Catalog }
        )
    }
    if (showAlarmResetConfirm) {
        AlertDialog(
            onDismissRequest = { showAlarmResetConfirm = false },
            title = { Text("复位报警?") },
            text = { Text("当前处于报警中状态。复位为本地操作,不会向平台发送 SIP。") },
            confirmButton = {
                TextButton(onClick = {
                    actions.onAlarmReset()
                    showAlarmResetConfirm = false
                    toast.info("已复位报警")
                }) { Text("复位") }
            },
            dismissButton = {
                TextButton(onClick = { showAlarmResetConfirm = false }) { Text("取消") }
            }
        )
    }
    detailFor?.let { kind ->
        SubscriptionDetailSheet(
            kind = kind,
            status = state.subscriptions[kind] ?: SubscriptionStatus(),
            onDismiss = { detailFor = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    recordingActive: Boolean = false,
    alarmActive: Boolean = false,
    subscribed: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val accent = when {
        recordingActive -> UvpColor.Danger
        alarmActive -> UvpColor.Warning
        enabled -> UvpColor.Primary
        else -> UvpColor.TextHint
    }
    val borderColor = if (!enabled && !recordingActive && !alarmActive) UvpColor.Border else accent
    val bg = when {
        recordingActive -> UvpColor.DangerBg
        alarmActive -> UvpColor.WarningBg
        else -> UvpColor.Surface
    }
    val pulsing = recordingActive || alarmActive
    val pulseColor = if (recordingActive) UvpColor.Danger else UvpColor.Warning
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accent
            )
            Text(
                label, fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = accent
            )
            Text(
                when {
                    recordingActive -> "录像中"
                    alarmActive -> "报警中"
                    enabled -> "可触发"
                    else -> "未就绪"
                },
                fontSize = 8.5.sp,
                color = accent.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )
        }
        if (pulsing) {
            // 右上角脉动点 — 录像红 / 报警橙,主屏一眼可见
            val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "tile-pulse")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(durationMillis = 800),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "tile-alpha"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(pulseColor.copy(alpha = alpha))
            )
        }
        // 订阅角标:平台 Event:Alarm 订阅活跃且非报警态 → 静态绿点(不脉动,区别于报警橙点)
        if (subscribed && !pulsing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(UvpColor.Success)
            )
        }
    }
}

private val SubscriptionKind.title: String
    get() = when (this) {
        SubscriptionKind.MobilePosition -> "位置订阅"
        SubscriptionKind.Catalog -> "目录订阅"
        SubscriptionKind.Alarm -> "报警订阅"
    }

@Composable
private fun SubscriptionTile(
    icon: ImageVector,
    label: String,
    status: SubscriptionStatus,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tone = if (status.active) UvpColor.SuccessText else UvpColor.TextHint
    val border = if (status.active) UvpColor.SuccessBorder else UvpColor.Border
    val bg = if (status.active) UvpColor.SuccessBg else UvpColor.Surface
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Icon(
            icon, contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tone
        )
        Text(
            label, fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = tone
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(4.dp))
            Text(
                if (status.active) "已订阅" else "未订阅",
                fontSize = 8.5.sp,
                color = tone.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionDetailSheet(
    kind: SubscriptionKind,
    status: SubscriptionStatus,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = UvpColor.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(kind.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                if (status.active) "上级平台已订阅,设备按周期 NOTIFY"
                else "上级平台尚未发起 SUBSCRIBE",
                fontSize = 12.sp, color = UvpColor.TextSecondary
            )
            Spacer(Modifier.height(16.dp))
            DetailKv("状态", if (status.active) "已订阅" else "未订阅")
            DetailKv("订阅者", status.subscriber ?: "—")
            DetailKv("Expires", status.expiresSeconds?.let { "${it}s" } ?: "—")
            DetailKv("剩余", status.remainingSeconds?.let { "${it}s" } ?: "—")
            DetailKv("Notify 计数", status.notifyCount.toString())
        }
    }
}

@Composable
private fun DetailKv(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, fontSize = 12.sp, color = UvpColor.TextHint,
            modifier = Modifier.width(80.dp))
        Text(value, fontSize = 12.sp, color = UvpColor.Text,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

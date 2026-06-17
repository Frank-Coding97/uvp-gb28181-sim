package com.uvp.sim.ui.simulate

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.UvpColor

/**
 * "模拟"tab 主屏(M2 §4 设备控制 + 3D 模拟中心).
 *
 * 布局:
 * - 上 70%: [Camera3DView] 3D 摄像机模型(Android: Filament; iOS: SceneKit;
 *   Desktop: 占位)
 * - 下 30%: [PtzHudPanel] 平台控制指令实时解码 HUD
 *
 * 数据源:[AppUiState.deviceControlState] StateFlow 写入,UI 订阅.
 *
 * 注意:T14 临时实现 — AppUiState 还没加 deviceControlState 字段,
 * 这里先用空 default,等 platform shell 接 SimulatorEngine.deviceControlState
 * 时补上(留给 T16 收尾).
 */
@Composable
fun SimulateScreen(state: AppUiState, modifier: Modifier = Modifier) {
    val deviceControl = state.deviceControl
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UvpColor.Bg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        MonitoringStage(
            state = deviceControl,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        PtzHudPanel(
            state = deviceControl,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )
    }
}

@Composable
private fun MonitoringStage(
    state: DeviceControlState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = UvpColor.Surface,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(UvpColor.PrimaryLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = UvpColor.Primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Text(
                    "实时模拟画面",
                    modifier = Modifier.padding(start = 8.dp),
                    color = UvpColor.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (hasMotion(state)) UvpColor.Success else UvpColor.Border)
                )
                Text(
                    if (hasMotion(state)) "控制中" else "待命",
                    modifier = Modifier.padding(start = 5.dp),
                    color = if (hasMotion(state)) UvpColor.SuccessText else UvpColor.TextHint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                Box(Modifier.weight(1f))
                CompactMetric("Pan", formatSignedAngle(state.panAngle))
                Box(Modifier.width(8.dp))
                CompactMetric("Tilt", formatSignedAngle(state.tiltAngle))
                Box(Modifier.width(8.dp))
                CompactMetric("Zoom", "%.1fx".format(state.zoomLevel))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            CameraStageTop,
                            CameraStageMid,
                            CameraStageBottom
                        )
                    )
                )
                .border(
                    1.dp,
                    UvpColor.BorderLight,
                    RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                )
        ) {
            CameraGlbView(
                state = state,
                modifier = Modifier.fillMaxSize()
            )
            StageOverlay(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                state = state
            )
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            label,
            color = UvpColor.TextHint,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            color = UvpColor.Text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StageOverlay(modifier: Modifier = Modifier, state: DeviceControlState) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OverlayPill(
            icon = Icons.Outlined.CenterFocusWeak,
            label = state.lastCommand?.type ?: "NO CMD",
            active = state.lastCommand != null
        )
        Box(Modifier.width(6.dp))
        OverlayPill(
            icon = Icons.Outlined.Memory,
            label = when {
                state.isRebooting -> "REBOOT"
                state.isAlarming -> "ALARM"
                state.isRecording -> "REC"
                state.isGuarded -> "GUARD"
                else -> "ONLINE"
            },
            active = state.isRebooting || state.isAlarming || state.isRecording || state.isGuarded
        )
    }
}

@Composable
private fun OverlayPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
) {
    val fg = if (active) UvpColor.PrimaryDark else UvpColor.TextSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(UvpColor.Surface.copy(alpha = 0.86f))
            .border(1.dp, UvpColor.BorderLight, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
        Text(
            label,
            modifier = Modifier.padding(start = 4.dp),
            color = fg,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun hasMotion(state: DeviceControlState): Boolean {
    return state.panSpeed != 0f || state.tiltSpeed != 0f || state.zoomSpeed != 0f
}

private fun formatSignedAngle(value: Float): String {
    val rounded = kotlin.math.round(value).toInt()
    return if (rounded > 0) "+$rounded°" else "$rounded°"
}

private val CameraStageTop = androidx.compose.ui.graphics.Color(0xFFF6F9FD)
private val CameraStageMid = androidx.compose.ui.graphics.Color(0xFFE4ECF7)
private val CameraStageBottom = androidx.compose.ui.graphics.Color(0xFFD7E2F1)

/**
 * 加载 .glb 摄像机模型(C 方案 2026-06-13).
 * - Android: Filament + gltfio,加载 assets/security_camera.glb
 * - iOS: SceneKit 占位(待 cinterop)
 */
@Composable
expect fun CameraGlbView(state: DeviceControlState, modifier: Modifier)

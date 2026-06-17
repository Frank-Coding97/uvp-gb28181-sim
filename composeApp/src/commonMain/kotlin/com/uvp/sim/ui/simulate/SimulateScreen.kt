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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.UvpColor
import kotlinx.coroutines.delay

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
                Box(Modifier.weight(1f))
                StatusHeadline(state = state)
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
        }
    }
}

/**
 * 顶部状态短句 — "现在平台/设备到底在干什么"维度的文案,
 * 跟底部 StatusDot(REC/GUARD/ALARM/REBOOT 持续开关状态) 不重叠.
 *
 * 优先级: 开机自检中 > PTZ 运动中 > 刚收到平台命令 (3s 内) > 等待中.
 */
@Composable
private fun StatusHeadline(state: DeviceControlState) {
    val nowMs = useTickingNow(intervalMs = 500L)
    val mountMs = remember { currentTimeMs() }
    val selfTesting = (nowMs - mountMs) in 0..6_500
    val cmd = state.lastCommand
    val recentCmd = cmd != null && (nowMs - cmd.timestampMs) in 0..3_000

    val (text, color, dotColor) = when {
        selfTesting -> Triple("开机自检中", UvpColor.Primary, UvpColor.Primary)
        hasMotion(state) -> Triple("PTZ 运动中", UvpColor.Primary, UvpColor.Primary)
        recentCmd -> Triple("刚收到 ${cmd!!.type}", UvpColor.SuccessText, UvpColor.Success)
        else -> Triple("等待平台下发控制指令", UvpColor.TextHint, UvpColor.Border)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text,
            modifier = Modifier.padding(start = 6.dp),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 周期性返回当前墙上时间(ms),让基于"距离命令多久"的 UI 状态随时间自然失效.
 * 不依赖平台 API,纯 Compose + kotlinx.coroutines.delay.
 */
@Composable
private fun useTickingNow(intervalMs: Long): Long {
    var now by remember { mutableStateOf(currentTimeMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            now = currentTimeMs()
        }
    }
    return now
}

private fun currentTimeMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

internal fun hasMotion(state: DeviceControlState): Boolean {
    return state.panSpeed != 0f || state.tiltSpeed != 0f || state.zoomSpeed != 0f
}

private val CameraStageTop = Color(0xFF3D5A85)
private val CameraStageMid = Color(0xFF2A4068)
private val CameraStageBottom = Color(0xFF182B49)

/**
 * 加载 .glb 摄像机模型(C 方案 2026-06-13).
 * - Android: Filament + gltfio,加载 assets/security_camera.glb
 * - iOS: SceneKit 占位(待 cinterop)
 */
@Composable
expect fun CameraGlbView(state: DeviceControlState, modifier: Modifier)

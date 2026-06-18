package com.uvp.sim.ui.simulate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.DragZoomRect
import com.uvp.sim.ui.AppActions
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
fun SimulateScreen(state: AppUiState, actions: AppActions, modifier: Modifier = Modifier) {
    val deviceControl = state.deviceControl

    // SnapshotFlash 全屏快门白光
    val snapshotFlashAlpha = remember { Animatable(0f) }
    // IFrameFlash 角标
    var iframeChipVisible by remember { mutableStateOf(false) }
    // ConfigChanged / DeviceUpgrade / FormatSDCard 三类 snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // 5 个 effect 订阅(Reboot / HomePosition / PresetRecall / PrecisePoseGoto 由 CameraGlbView 内部消费)
    LaunchedEffect(deviceControl.pendingEffect) {
        when (val e = deviceControl.pendingEffect) {
            is DeviceEffect.SnapshotFlash -> {
                snapshotFlashAlpha.snapTo(0.85f)
                delay(80)
                snapshotFlashAlpha.animateTo(0f, animationSpec = tween(80))
            }
            is DeviceEffect.IFrameFlash -> {
                iframeChipVisible = true
                delay(700)  // 入 150 + 维持 250 + 出 300
                iframeChipVisible = false
            }
            is DeviceEffect.ConfigChanged -> {
                snackbarHostState.showSnackbar("配置已更新: ${e.changedFields.joinToString(", ")}")
            }
            is DeviceEffect.DeviceUpgradeRequested -> {
                snackbarHostState.showSnackbar("收到设备升级请求(模拟): v${e.firmware}")
            }
            is DeviceEffect.FormatSDCardRequested -> {
                snackbarHostState.showSnackbar("格式化 SD 卡(模拟): card ${e.cardIndex}")
            }
            else -> { /* 余下交给 CameraGlbView 处理 */ }
        }
        if (deviceControl.pendingEffect != null) {
            // 给 CameraGlbView LaunchedEffect 一帧消费时间,然后兜底清零
            delay(50)
            actions.onConsumeDeviceEffect()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(UvpColor.Bg)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            MonitoringStage(
                state = deviceControl,
                iframeChipVisible = iframeChipVisible,
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

        // 全屏快门白光覆盖层(在 Column 之上,SnackbarHost 之下)
        if (snapshotFlashAlpha.value > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = snapshotFlashAlpha.value))
            )
        }

        // Snackbar host(浮在最上层)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun MonitoringStage(
    state: DeviceControlState,
    iframeChipVisible: Boolean,
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

            // GuardCmd 力场罩(径向渐变光圈 + 边缘描边)— state.isGuarded 切换时 600ms 淡入/淡出
            GuardOverlay(
                isGuarded = state.isGuarded,
                modifier = Modifier.fillMaxSize()
            )

            // DragZoom 线框可视化(平台拉框聚焦)— state.dragZoomRect 写入时 200ms 入 / 1.4× 放大 / 600ms 淡出
            DragZoomOverlay(
                rect = state.dragZoomRect,
                modifier = Modifier.fillMaxSize()
            )

            // IFameCmd 关键帧角标(右上角)— DeviceEffect.IFrameFlash 触发,250ms 维持
            if (iframeChipVisible) {
                IFrameChip(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                )
            }
        }
    }
}

/**
 * 布防力场罩 — `state.isGuarded` 为 true 时叠加径向渐变绿光圈 + 边缘描边.
 * 600ms 淡入,撤防 600ms 淡出. 不抢点击事件(纯绘制层).
 */
@Composable
private fun GuardOverlay(isGuarded: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (isGuarded) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "guard-alpha"
    )
    if (alpha < 0.01f) return
    Canvas(modifier = modifier) {
        val maxR = size.minDimension * 0.6f
        // 径向渐变(中心透明 → 中间浅绿光晕 → 边缘透明)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    UvpColor.Success.copy(alpha = 0.18f * alpha),
                    UvpColor.Success.copy(alpha = 0.32f * alpha),
                    Color.Transparent,
                ),
                center = center,
                radius = maxR,
            ),
            center = center,
            radius = maxR,
        )
        // 边缘绿色描边(整个 3D 区四周)
        drawRect(
            color = UvpColor.Success.copy(alpha = 0.5f * alpha),
            topLeft = Offset.Zero,
            size = size,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/**
 * DragZoom 线框可视化 — `state.dragZoomRect` 写入时画青色矩形 → 缓动放大 1.4× → 淡出.
 *
 * GB28181 §F DragZoom 坐标系: midX/midY/lengthX/lengthY 在 0-1000 归一化空间.
 * 映射到 3D 区像素时按 size.width/1000 缩放.
 */
@Composable
private fun DragZoomOverlay(rect: DragZoomRect?, modifier: Modifier = Modifier) {
    if (rect == null) return
    var alpha by remember(rect) { mutableStateOf(0f) }
    var scale by remember(rect) { mutableStateOf(1f) }
    LaunchedEffect(rect) {
        // 200ms 入(0→1)
        val steps = 12
        repeat(steps) { i ->
            alpha = (i + 1).toFloat() / steps
            delay(200L / steps)
        }
        // 1.5s 维持 + 1s 缓动放大到 1.4×
        val growSteps = 30
        repeat(growSteps) { i ->
            val p = (i + 1).toFloat() / growSteps
            scale = 1f + 0.4f * (0.5f - 0.5f * kotlin.math.cos(p * kotlin.math.PI.toFloat()))
            delay(1000L / growSteps)
        }
        // 600ms 淡出
        val fadeSteps = 18
        repeat(fadeSteps) { i ->
            alpha = 1f - (i + 1).toFloat() / fadeSteps
            delay(600L / fadeSteps)
        }
    }
    Canvas(modifier = modifier) {
        val sx = size.width / 1000f
        val sy = size.height / 1000f
        val w = (rect.lengthX * sx) * scale
        val h = (rect.lengthY * sy) * scale
        val cx = rect.midX * sx
        val cy = rect.midY * sy
        drawRect(
            color = UvpColor.Info.copy(alpha = alpha),
            topLeft = Offset(cx - w / 2f, cy - h / 2f),
            size = Size(w, h),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun IFrameChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = UvpColor.Info.copy(alpha = 0.95f),
        border = androidx.compose.foundation.BorderStroke(1.dp, UvpColor.Info)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.CenterFocusWeak,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "I-FRAME",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 顶部状态短句 — "现在平台/设备到底在干什么"维度的文案,
 * 跟底部 StatusDot(REC/GUARD/ALARM/REBOOT 持续开关状态) 不重叠.
 *
 * 优先级: 远程重启中 > 开机自检中 > 预置位调用 > PTZ 运动中 > 刚收到平台命令 (3s 内) > 等待中.
 */
@Composable
private fun StatusHeadline(state: DeviceControlState) {
    val nowMs = useTickingNow(intervalMs = 500L)
    val mountMs = remember { currentTimeMs() }
    val selfTesting = (nowMs - mountMs) in 0..6_500
    val cmd = state.lastCommand
    val recentCmd = cmd != null && (nowMs - cmd.timestampMs) in 0..3_000
    val effect = state.pendingEffect

    val (text, color, dotColor) = when {
        effect is DeviceEffect.Reboot -> Triple("远程重启中", UvpColor.Primary, UvpColor.Primary)
        selfTesting -> Triple("开机自检中", UvpColor.Primary, UvpColor.Primary)
        effect is DeviceEffect.PresetRecall ->
            Triple("预置位 P${effect.index} 调用中", UvpColor.Primary, UvpColor.Primary)
        effect is DeviceEffect.PrecisePoseGoto ->
            Triple("精确控制 → ${formatSignedAngle(effect.targetPose.pan)} / ${formatSignedAngle(effect.targetPose.tilt)}",
                UvpColor.Primary, UvpColor.Primary)
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

private fun formatSignedAngle(value: Float): String {
    val rounded = kotlin.math.round(value).toInt()
    return if (rounded > 0) "+$rounded°" else "$rounded°"
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

package com.uvp.sim.ui.simulate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.DeviceEffectDto
import com.uvp.sim.ui.model.DragZoomRectDto
import com.uvp.sim.gb28181.AuxFunction
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
 * 数据源:[AppUiState.deviceControl] StateFlow 写入,UI 订阅.
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
            is DeviceEffectDto.SnapshotFlash -> {
                snapshotFlashAlpha.snapTo(0.85f)
                delay(80)
                snapshotFlashAlpha.animateTo(0f, animationSpec = tween(80))
            }
            is DeviceEffectDto.IFrameFlash -> {
                iframeChipVisible = true
                delay(700)  // 入 150 + 维持 250 + 出 300
                iframeChipVisible = false
            }
            is DeviceEffectDto.ConfigChanged -> {
                snackbarHostState.showSnackbar("配置已更新: ${e.changedFields.joinToString(", ")}")
            }
            is DeviceEffectDto.DeviceUpgradeRequested -> {
                snackbarHostState.showSnackbar("收到设备升级请求(模拟): v${e.firmware}")
            }
            is DeviceEffectDto.FormatSDCardRequested -> {
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
                onPoseTick = actions::onPoseTick,
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
    state: DeviceControlDto,
    iframeChipVisible: Boolean,
    onPoseTick: (Float, Float, Float) -> Unit,
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
                    "模拟中心",
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
                .background(CameraStageBase)  // Filament 之下兜底色,跟 clearColor 同色
                .border(
                    1.dp,
                    UvpColor.BorderLight,
                    RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                )
        ) {
            CameraGlbView(
                state = state,
                onPoseTick = onPoseTick,
                modifier = Modifier.fillMaxSize()
            )

            // 磨砂玻璃质感叠层(在 3D 之上,GuardOverlay 之下)
            // 中心保持透明不影响球机,只在边缘 / 上下沿透出"光透磨砂"的高级感
            FrostedGlassOverlay(modifier = Modifier.fillMaxSize())

            // 辅助控制 3D 视觉反馈(雨刷扫动 + 红外灯暗绿夜视滤镜)
            AuxFeedbackOverlay(state = state, modifier = Modifier.fillMaxSize())

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

            // IFameCmd 关键帧角标(右上角)— DeviceEffectDto.IFrameFlash 触发,250ms 维持
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
 * 辅助控制 3D 视觉反馈:
 * - 雨刷(Wiper):屏幕边缘画一道半透明 wiper 弧线,2s 来回扫一次
 * - 红外灯(InfraredLight):整画面叠暗绿色滤镜 + 右上角 IR 标识
 * - 加热(Heater):右上角小图标 + 暖色微调
 * - 除雾(Defog):画面四角"清晰"指示
 * - 制冷(Cooler):右上角冷蓝指示
 *
 * 多个 Aux 同时 ON 不冲突,叠加显示.
 */
@Composable
private fun AuxFeedbackOverlay(state: DeviceControlDto, modifier: Modifier = Modifier) {
    val wiperOn = state.auxStates[AuxFunction.Wiper.index] == true
    val irOn = state.auxStates[AuxFunction.InfraredLight.index] == true

    Box(modifier = modifier) {
        // 红外灯滤镜:整画面叠暗绿色 0.18 alpha + 中心十字
        if (irOn) {
            val alpha by animateFloatAsState(
                targetValue = if (irOn) 1f else 0f,
                animationSpec = tween(400),
                label = "ir-filter"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0BAA50).copy(alpha = 0.18f * alpha))
            )
            // 右上角 IR 角标
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF0BAA50).copy(alpha = 0.9f * alpha),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))
            ) {
                Text(
                    "IR 夜视",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        // 雨刷:Canvas 画一道弧线 wiper,2s 来回扫
        if (wiperOn) {
            val infinite = rememberInfiniteTransition(label = "wiper")
            val sweepProgress by infinite.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2000),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "wiper-sweep"
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // 雨刷柄从底部中心,扫角 -45° → +45°
                val angleDeg = -45f + sweepProgress * 90f
                val rad = angleDeg * kotlin.math.PI.toFloat() / 180f
                val pivotX = w / 2f
                val pivotY = h * 1.05f
                val length = h * 0.95f
                val tipX = pivotX + kotlin.math.sin(rad) * length
                val tipY = pivotY - kotlin.math.cos(rad) * length

                // 雨刷臂(细线 + 半透明黑)
                drawLine(
                    color = Color.Black.copy(alpha = 0.35f),
                    start = androidx.compose.ui.geometry.Offset(pivotX, pivotY),
                    end = androidx.compose.ui.geometry.Offset(tipX, tipY),
                    strokeWidth = 3.dp.toPx()
                )
                // 雨刷胶条(略宽 + 更深)
                drawLine(
                    color = Color.Black.copy(alpha = 0.55f),
                    start = androidx.compose.ui.geometry.Offset(
                        pivotX + kotlin.math.sin(rad) * (length * 0.3f),
                        pivotY - kotlin.math.cos(rad) * (length * 0.3f)
                    ),
                    end = androidx.compose.ui.geometry.Offset(tipX, tipY),
                    strokeWidth = 6.dp.toPx()
                )
            }
            // 右上角 雨刷 角标
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 38.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF1890FF).copy(alpha = 0.9f),
            ) {
                Text(
                    "雨刷工作中",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
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
private fun DragZoomOverlay(rect: DragZoomRectDto?, modifier: Modifier = Modifier) {
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
private fun StatusHeadline(state: DeviceControlDto) {
    val nowMs = useTickingNow(intervalMs = 500L)
    val mountMs = remember { currentTimeMs() }
    val selfTesting = (nowMs - mountMs) in 0..6_500
    val cmd = state.lastCommand
    val recentCmd = cmd != null && (nowMs - cmd.timestampMs) in 0..3_000
    val effect = state.pendingEffect

    val (text, color, dotColor) = when {
        effect is DeviceEffectDto.Reboot -> Triple("远程重启中", UvpColor.Primary, UvpColor.Primary)
        selfTesting -> Triple("开机自检中", UvpColor.Primary, UvpColor.Primary)
        effect is DeviceEffectDto.PresetRecall ->
            Triple("预置位 P${effect.index} 调用中", UvpColor.Primary, UvpColor.Primary)
        effect is DeviceEffectDto.PrecisePoseGoto ->
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
internal fun useTickingNow(intervalMs: Long): Long {
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

internal fun hasMotion(state: DeviceControlDto): Boolean {
    return state.panSpeed != 0f || state.tiltSpeed != 0f || state.zoomSpeed != 0f
}

private val CameraStageBase = Color(0xFF0E1A36)  // 中性深紫蓝(午夜蓝),跟 Filament clearColor 同色,给 Compose 层兜底

/** 磨砂玻璃质感叠层 — 在 Filament 3D 渲染上方覆盖三层光晕,
 *  中心保持透明,只在边缘/对角辐射出青蓝 + 紫蓝光斑,模拟 Win10 Acrylic / Big Sur. */
@Composable
private fun FrostedGlassOverlay(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        // 第 1 层:左上青蓝光斑(科技感),径向渐变中心偏左上
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1890FF).copy(alpha = 0.22f),
                            Color(0xFF1890FF).copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                        center = Offset(0f, 0f),
                        radius = 1200f,
                    )
                )
        )
        // 第 2 层:右下紫蓝光斑(高级感),径向渐变中心偏右下
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF7C5DDF).copy(alpha = 0.18f),
                            Color(0xFF7C5DDF).copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                        radius = 1100f,
                    )
                )
        )
        // 第 3 层:顶部 1dp 高光线(模拟玻璃顶边反光)+ 整体微白朦胧
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.03f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f),  // 底部暗角增加深度
                        )
                    )
                )
        )
    }
}

/**
 * 加载 .glb 摄像机模型(C 方案 2026-06-13).
 * - Android: Filament + gltfio,加载 assets/security_camera.glb
 * - iOS: SceneKit 占位(待 cinterop)
 */
@Composable
expect fun CameraGlbView(
    state: DeviceControlDto,
    onPoseTick: (Float, Float, Float) -> Unit,
    modifier: Modifier
)

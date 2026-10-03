package com.uvp.sim.ui.simulate

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.gb28181.AuxFunction
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.DragZoomRectDto
import com.uvp.sim.ui.model.TargetTrackDto
import com.uvp.sim.ui.model.TargetTrackModeDto
import kotlinx.coroutines.delay

/**
 * 辅助控制视觉反馈 —— 只有雨刷一项。
 *
 * - 雨刷(Wiper):屏幕边缘画一道半透明 wiper 弧线,2s 来回扫一次
 *
 * ⛔ 红外灯 / 加热 / 除雾 / 制冷的叠加已移除(2026-09-21):它们对应的辅助开关编号
 * (2~5)在 GB/T 28181 A.3.7(表 A.11)里没有语义,标准只钉了编号 1 = 雨刷。
 * 保留叠加等于替标准"发明"了四种开关,与本仓只做标准的定位冲突。
 */
@Composable
internal fun AuxFeedbackOverlay(state: DeviceControlDto, modifier: Modifier = Modifier) {
    val wiperOn = state.auxStates[AuxFunction.Wiper.index] == true

    Box(modifier = modifier) {
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
                    start = Offset(pivotX, pivotY),
                    end = Offset(tipX, tipY),
                    strokeWidth = 3.dp.toPx()
                )
                // 雨刷胶条(略宽 + 更深)
                drawLine(
                    color = Color.Black.copy(alpha = 0.55f),
                    start = Offset(
                        pivotX + kotlin.math.sin(rad) * (length * 0.3f),
                        pivotY - kotlin.math.cos(rad) * (length * 0.3f)
                    ),
                    end = Offset(tipX, tipY),
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
internal fun GuardOverlay(isGuarded: Boolean, modifier: Modifier = Modifier) {
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

/** 拉框线框在**画布像素**里的位置（供绘制与单测共用）。 */
internal data class DragZoomWireRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * 平台拉框 → 画布线框位置。
 *
 * ⛔⛔ 坐标口径是**播放窗口像素**，不是 0~1000 归一化。标准附录 A.2.3.1.8/.9 的注：
 * 「命令中的坐标系以播放窗口的左上角原点，各坐标取值以像素单位」——
 * 所以换算就是「除以报文里的 `Length` / `Width`」这一件事，分母**必须是报文里带来的那两把尺子**
 * （平台即按自己的播放窗口算出它们）。2026-09-20 前这里按 `size.width / 1000f` 缩放，
 * 等于把"播放窗口 1000px"当成永远成立：窗口宽 >1000px 时线框直接画到画布外面去，
 * 而画得出来、不报错，肉眼只看到"框好像偏了"。
 *
 * @return null = 这份报文画不出框（缺 `Length`/`Width`，或框/画布尺寸退化）。
 */
internal fun dragZoomWireRect(
    rect: DragZoomRectDto,
    canvasWidth: Float,
    canvasHeight: Float,
    scale: Float = 1f,
): DragZoomWireRect? {
    if (rect.frameLength <= 0 || rect.frameWidth <= 0) return null
    if (rect.lengthX <= 0 || rect.lengthY <= 0) return null
    if (canvasWidth <= 0f || canvasHeight <= 0f) return null
    val centerX = rect.midX.toFloat() / rect.frameLength * canvasWidth
    val centerY = rect.midY.toFloat() / rect.frameWidth * canvasHeight
    val width = rect.lengthX.toFloat() / rect.frameLength * canvasWidth * scale
    val height = rect.lengthY.toFloat() / rect.frameWidth * canvasHeight * scale
    return DragZoomWireRect(centerX - width / 2f, centerY - height / 2f, width, height)
}

/**
 * DragZoom 线框可视化 — `state.dragZoomRect` 写入时画青色矩形 → 缓动放大 1.4× → 淡出.
 *
 * ⚠️ 这是**本机画布上的示意**，不是功能本身：拉框放大/缩小真正生效的地方是
 * `deviceControlState.dragZoomViewport` → 真流裁剪（Android `CameraTexturePass` /
 * iOS `IosFrameProcessor`）。画布与推流是两条互不相干的链路（同 `FrameMirror` 那次的结论）。
 */
@Composable
internal fun DragZoomOverlay(rect: DragZoomRectDto?, modifier: Modifier = Modifier) {
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
        val wire = dragZoomWireRect(rect, size.width, size.height, scale) ?: return@Canvas
        drawRect(
            color = UvpColor.Info.copy(alpha = alpha),
            topLeft = Offset(wire.left, wire.top),
            size = Size(wire.width, wire.height),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** 磨砂玻璃质感叠层 — 在 Filament 3D 渲染上方覆盖三层光晕,
 *  中心保持透明,只在边缘/对角辐射出青蓝 + 紫蓝光斑,模拟 Win10 Acrylic / Big Sur. */
@Composable
internal fun FrostedGlassOverlay(modifier: Modifier = Modifier) {
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

// ===== GB/T 28181-2022 A.2.3.1.14 目标跟踪 =====

/**
 * 角标文案 —— 抽成纯函数以便直接单测（范式同 `ptz/` 下的 `*StateTest`）。
 *
 * ⛔⛔ **两种模式的文案必须不同，这是本函数存在的唯一理由。**
 * `Manual` 的框是**平台报文里的真值**（按 `Length`/`Width` 两把尺子做的比值换算，
 * 与平台面板上框的位置一一对应）；`Auto` 的框是模拟器**编的**（设备 AI 没接真源，
 * 见 `TargetTrackState.SIMULATED_AUTO_BOX`）。把后者写成"目标跟踪 · 自动"，
 * 演示时就会被当成"设备真的自己找到目标了"—— 而这条命令恰恰是**无应答命令**
 * （9.3.1 d)，表 1 序号 13 应答栏"（无）"），平台上没有任何东西能证伪它。
 *
 * ⛔ `Manual` 且没有框（报文没带 / 半份坐标 / 整框在画面外）时说"无框选区域"，
 * **不回落成"框选成功"**：那会让"平台框选没生效"在设备屏幕上看起来是生效的。
 */
internal fun targetTrackOverlayLabel(track: TargetTrackDto): String = when (track.mode) {
    TargetTrackModeDto.Manual ->
        if (track.box == null) "目标跟踪 · 手动 · 无框选区域" else "目标跟踪 · 手动"
    TargetTrackModeDto.Auto -> "目标跟踪 · 自动（模拟目标）"
}

/**
 * 目标跟踪的可视化 —— 跟踪框 + 左上角模式角标。
 *
 * ## 与 [DragZoomOverlay] 的两处关键差别（别照着那个改）
 *
 * 1. **持续显示，不淡出**。拉框放大是一次性动作（效果在真流视窗里，画布的线框只是"收到过"的
 *    提示，所以 200ms 入 / 1.4× 放大 / 600ms 淡出）；而跟踪是一个**持续状态**：
 *    标准里 `Stop` 才是终点（A.2.3.1.14 的 `TargetTrack` 元素取值 `Auto|Manual|Stop`）。
 *    跟着一起淡出的话，设备屏幕上"正在跟踪"会在 2 秒后自己消失 —— 而平台侧既没回执
 *    也没有任何查询命令能读回跟踪态（9.3.1 d) + 附录 A 无此项），现场只能看到
 *    "平台点了没反应"。
 * 2. **颜色用 [UvpColor.Warning]（橙）而不是拉框用的 [UvpColor.Info]（紫）**：
 *    两个框同时存在是可能的（平台先拉框放大、再手动跟踪），同色会让操作员分不清
 *    哪个是"放大区域"哪个是"跟踪目标"。
 *
 * ## 线框形状
 * 矩形描边 + 四角加粗的"取景框"角标 —— 与拉框的纯矩形一眼可分，语义也更贴"锁定一个目标"。
 *
 * ⚠️ 坐标口径：`track.box` 已经是**归一化 0~1** 的（见 `TargetTrackBox`），
 * 这里只做「[0,1] → 画布像素」一次线性映射。⛔ 别在这里再除一次 `Length`/`Width`：
 * 归一化只做一次、只在自己的那一层做（同 `VideoMaskOverlay.of` 的规矩）——
 * 做两遍不会报错，只会让框整体缩小且位置偏向画面左上角。
 */
@Composable
internal fun TargetTrackOverlay(track: TargetTrackDto?, modifier: Modifier = Modifier) {
    if (track == null) return
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val box = track.box ?: return@Canvas
            val left = box.left * size.width
            val top = box.top * size.height
            val right = (box.left + box.width) * size.width
            val bottom = (box.top + box.height) * size.height

            drawRect(
                color = UvpColor.Warning.copy(alpha = 0.9f),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 2.dp.toPx()),
            )
            // 四角取景框角标：每角两条短线，长度随框尺寸收缩（小框上别把角标画得比框还大）。
            val tick = minOf(12.dp.toPx(), (right - left) / 3f, (bottom - top) / 3f)
            val w = 3.dp.toPx()
            val corners = listOf(
                // 左上
                Offset(left, top) to Offset(left + tick, top),
                Offset(left, top) to Offset(left, top + tick),
                // 右上
                Offset(right, top) to Offset(right - tick, top),
                Offset(right, top) to Offset(right, top + tick),
                // 左下
                Offset(left, bottom) to Offset(left + tick, bottom),
                Offset(left, bottom) to Offset(left, bottom - tick),
                // 右下
                Offset(right, bottom) to Offset(right - tick, bottom),
                Offset(right, bottom) to Offset(right, bottom - tick),
            )
            for ((start, end) in corners) {
                drawLine(color = UvpColor.Warning, start = start, end = end, strokeWidth = w)
            }
        }

        // 角标贴在**右下角**：左上角那一列已经被 Aux 的 IR / 雨刷角标占着
        // （见 [AuxFeedbackOverlay]，两个都按 TopStart 起排）。
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
            shape = RoundedCornerShape(4.dp),
            color = UvpColor.Warning.copy(alpha = 0.92f),
        ) {
            Text(
                targetTrackOverlayLabel(track),
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

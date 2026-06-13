package com.uvp.sim.ui.simulate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.uvp.sim.domain.DeviceControlState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 跨平台等距投影摄像机视图(B 方案,2026-06-13 替换 Filament 3D).
 *
 * 设计原则:
 * - 矢量绘制,Compose Canvas,不依赖 GL / 第三方 3D 库
 * - 等距投影(2D 模拟 3D 效果),视觉接近产品图
 * - PTZ 控制 → 直接旋转矢量节点
 * - 状态灯 = 实心圆 + glow halo
 *
 * 优点:不卡 / APK 小 / Android+iOS+Desktop 同一份代码 / 视觉干净.
 */
@Composable
fun CameraIsoView(state: DeviceControlState, modifier: Modifier = Modifier) {
    // 帧驱动 PTZ 速率积分(替代 Choreographer)
    var panAngle by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var tiltAngle by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var zoomLevel by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1000f
                last = now
                panAngle = (panAngle + state.panSpeed * dt).coerceIn(-180f, 180f)
                tiltAngle = (tiltAngle + state.tiltSpeed * dt).coerceIn(-90f, 90f)
                zoomLevel = (zoomLevel + state.zoomSpeed * dt).coerceIn(1f, 16f)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFEEF2F7), Color(0xFFD9E2EC))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val unit = max(size.width, size.height) / 12f

            // 地面影子
            drawShadow(cx, cy + unit * 1.6f, unit * 2.4f, unit * 0.6f)

            // 底座(梯形 + 顶面椭圆)
            translate(cx, cy + unit * 0.9f) {
                drawBase(unit)
            }
            // 摇头机构(球形关节,绕 Y 轴 = pan)
            translate(cx, cy + unit * 0.5f) {
                drawJoint(unit)
            }
            // 镜筒 + 镜头(随 pan / tilt 旋转,zoom 改长度)
            translate(cx, cy + unit * 0.5f) {
                rotate(panAngle / 2f, pivot = Offset.Zero) {
                    rotate(-tiltAngle / 2f, pivot = Offset.Zero) {
                        drawBarrelAndLens(unit, zoomLevel)
                    }
                }
            }
            // 状态 LED
            translate(cx - unit * 1.0f, cy + unit * 1.3f) {
                drawLed(unit, on = !state.isRebooting,
                    color = Color(0xFF52C41A), label = "PWR")
            }
            translate(cx, cy + unit * 1.3f) {
                drawLed(unit, on = state.isRecording,
                    color = Color(0xFFFF4D4F), label = "REC")
            }
            translate(cx + unit * 1.0f, cy + unit * 1.3f) {
                drawLed(unit, on = state.isAlarming,
                    color = Color(0xFFFA541C), label = "ALM")
            }
            // 布防力场(半透明罩)
            if (state.isGuarded) {
                translate(cx, cy + unit * 0.5f) {
                    drawGuardField(unit * 3.5f)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShadow(
    cx: Float, cy: Float, rx: Float, ry: Float
) {
    val brush = Brush.radialGradient(
        colors = listOf(Color(0x40000000), Color(0x00000000)),
        center = Offset(cx, cy),
        radius = rx
    )
    drawOval(
        brush,
        topLeft = Offset(cx - rx, cy - ry),
        size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBase(unit: Float) {
    val w = unit * 2.4f
    val h = unit * 0.5f
    val taper = unit * 0.3f
    val path = Path().apply {
        moveTo(-w / 2, h)
        lineTo(w / 2, h)
        lineTo(w / 2 - taper, -h * 0.4f)
        lineTo(-w / 2 + taper, -h * 0.4f)
        close()
    }
    drawPath(
        path,
        Brush.verticalGradient(
            listOf(Color(0xFF6B7280), Color(0xFF374151)),
            startY = -h, endY = h
        )
    )
    drawPath(path, Color(0xFF1F2937), style = Stroke(width = unit * 0.04f))
    drawOval(
        Brush.verticalGradient(
            listOf(Color(0xFF9CA3AF), Color(0xFF4B5563)),
            startY = -h * 0.6f, endY = -h * 0.2f
        ),
        topLeft = Offset(-w / 2 + taper, -h * 0.5f),
        size = androidx.compose.ui.geometry.Size(w - taper * 2, h * 0.4f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawJoint(unit: Float) {
    val r = unit * 0.55f
    drawCircle(
        Brush.radialGradient(
            colors = listOf(Color(0xFFD1D5DB), Color(0xFF6B7280)),
            center = Offset(-r * 0.3f, -r * 0.3f),
            radius = r * 1.5f
        ),
        radius = r,
        center = Offset.Zero
    )
    drawCircle(
        Color(0xFF1F2937),
        radius = r,
        center = Offset.Zero,
        style = Stroke(width = unit * 0.04f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBarrelAndLens(
    unit: Float, zoom: Float
) {
    val length = unit * (1.5f + (zoom - 1f) * 0.15f)
    val barrelW = unit * 0.55f

    // 镜筒主体(梯形 — 远端略小,产生透视感)
    val path = Path().apply {
        moveTo(-barrelW / 2, -length)
        lineTo(barrelW / 2, -length)
        lineTo(barrelW / 2 * 0.85f, 0f)
        lineTo(-barrelW / 2 * 0.85f, 0f)
        close()
    }
    drawPath(
        path,
        Brush.verticalGradient(
            listOf(Color(0xFF374151), Color(0xFF1F2937)),
            startY = -length, endY = 0f
        )
    )
    drawPath(path, Color(0xFF000000), style = Stroke(width = unit * 0.04f))

    // 高光条
    drawLine(
        Color(0x80FFFFFF),
        start = Offset(-barrelW / 2 + unit * 0.08f, -length),
        end = Offset(-barrelW / 2 * 0.85f + unit * 0.08f, 0f),
        strokeWidth = unit * 0.05f
    )

    // 镜头(圆 + 同心反光圈)
    val lensR = barrelW / 2 * 1.05f
    drawCircle(
        Color(0xFF111827),
        radius = lensR,
        center = Offset(0f, -length)
    )
    drawCircle(
        Color(0xFF1F2937),
        radius = lensR,
        center = Offset(0f, -length),
        style = Stroke(width = unit * 0.06f)
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFF1E40AF), Color(0xFF000000)),
            center = Offset(-lensR * 0.3f, -length - lensR * 0.3f),
            radius = lensR
        ),
        radius = lensR * 0.75f,
        center = Offset(0f, -length)
    )
    // 镜头反光
    drawCircle(
        Color(0xCCFFFFFF),
        radius = lensR * 0.18f,
        center = Offset(-lensR * 0.35f, -length - lensR * 0.35f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLed(
    unit: Float, on: Boolean, color: Color, label: String
) {
    val r = unit * 0.18f
    val fill = if (on) color else Color(0xFFD1D5DB)
    if (on) {
        // glow halo
        drawCircle(
            color.copy(alpha = 0.3f),
            radius = r * 2.4f,
            center = Offset.Zero
        )
        drawCircle(
            color.copy(alpha = 0.5f),
            radius = r * 1.6f,
            center = Offset.Zero
        )
    }
    drawCircle(fill, radius = r, center = Offset.Zero)
    drawCircle(
        Color(0xFF1F2937),
        radius = r,
        center = Offset.Zero,
        style = Stroke(width = unit * 0.025f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGuardField(radius: Float) {
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0x4052C41A), Color(0x0052C41A))
        ),
        radius = radius,
        center = Offset.Zero
    )
    drawCircle(
        Color(0xFF52C41A).copy(alpha = 0.6f),
        radius = radius,
        center = Offset.Zero,
        style = Stroke(width = radius * 0.02f)
    )
}

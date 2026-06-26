package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto

/**
 * 摄像监控台 — 顶部标题栏 + 下方 3D Filament 视图 + 各种 overlay 叠层.
 *
 * 标题栏: 模拟中心 logo + StatusHeadline 状态短句.
 * 3D 区: CameraGlbView + FrostedGlass + AuxFeedback + Guard + DragZoom + IFrameChip.
 */
@Composable
internal fun MonitoringStage(
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

/** 中性深紫蓝(午夜蓝),跟 Filament clearColor 同色,给 Compose 层兜底. */
internal val CameraStageBase = Color(0xFF0E1A36)

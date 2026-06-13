package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.ui.UvpColor

/**
 * iOS Camera3DView (M2 §4 device-control 3D simulate center).
 *
 * T14 阶段:占位.T9 (SceneKit cinterop 桥接 + SCNView + 摄像机模型) 完成后替换.
 */
@Composable
actual fun Camera3DView(state: DeviceControlState, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(UvpColor.PrimaryLight),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "3D 摄像机视图(iOS SceneKit 待 T9)",
            fontSize = 11.sp,
            color = UvpColor.TextSecondary
        )
    }
}

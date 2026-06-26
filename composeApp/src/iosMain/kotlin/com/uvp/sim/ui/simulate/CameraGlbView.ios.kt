package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.UvpColor

/** iOS 占位:cinterop SceneKit 留下次. */
@Composable
actual fun CameraGlbView(
    state: DeviceControlDto,
    onPoseTick: (Float, Float, Float) -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier.fillMaxSize().background(UvpColor.PrimaryLight),
        contentAlignment = Alignment.Center
    ) {
        Text("3D 摄像机视图(iOS SceneKit 待 T9)",
            fontSize = 11.sp, color = UvpColor.TextSecondary)
    }
}

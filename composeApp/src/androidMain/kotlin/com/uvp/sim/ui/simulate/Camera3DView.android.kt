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
 * Android Camera3DView (M2 §4 device-control 3D simulate center).
 *
 * T14 阶段:仅提供占位实现,先让 SimulateScreen tab 可见 + 编译通过.
 * T6 已落地 [Camera3DSpike] (MeshFactory + Filament Engine 框架),
 * T7 (FilamentCameraModel + 真 Material) 完成后,这里把占位换成实际渲染.
 *
 * 技术债:T6 的 Camera3DSpike 用了 placeholder material(空 payload),
 * 直接挂上去会运行时 crash.等 T7 把 material 接入再切换.
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
            "3D 摄像机视图(T6-T13 实现中)\n" +
                "Filament 1.71.6 已引入,Material 资源待 T7 接入",
            fontSize = 11.sp,
            color = UvpColor.TextSecondary
        )
    }
}

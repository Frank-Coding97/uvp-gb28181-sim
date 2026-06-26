package com.uvp.sim.ui.simulate

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.uvp.sim.ui.model.DeviceControlDto

/**
 * 加载 .glb 摄像机模型(C 方案 2026-06-13).
 * - Android: Filament + gltfio,加载 assets/security_camera.glb
 * - iOS: SceneKit 占位(待 cinterop)
 *
 * 必须保留在 com.uvp.sim.ui.simulate 包内,跟 android / ios actual 一致.
 */
@Composable
expect fun CameraGlbView(
    state: DeviceControlDto,
    onPoseTick: (Float, Float, Float) -> Unit,
    modifier: Modifier
)

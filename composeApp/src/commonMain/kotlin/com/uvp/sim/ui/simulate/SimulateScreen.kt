package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.UvpColor

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
    ) {
        // 上半部分:等距投影摄像机视图
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.7f)
                .background(UvpColor.Surface),
            contentAlignment = Alignment.Center
        ) {
            CameraIsoView(
                state = deviceControl,
                modifier = Modifier.fillMaxSize()
            )
        }
        // 下半部分:PTZ HUD
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.3f)
                .padding(8.dp)
        ) {
            PtzHudPanel(state = deviceControl, modifier = Modifier.fillMaxSize())
        }
    }
}

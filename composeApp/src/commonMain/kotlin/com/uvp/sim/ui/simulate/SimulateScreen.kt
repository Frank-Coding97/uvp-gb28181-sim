package com.uvp.sim.ui.simulate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceEffectDto
import kotlinx.coroutines.delay

/**
 * "模拟"tab 主屏(M2 §4 设备控制 + 3D 模拟中心).
 *
 * 布局:
 * - 上 70%: [MonitoringStage] 3D 摄像机模型(Android: Filament; iOS: SceneKit;
 *   Desktop: 占位) + 各种 overlay
 * - 下 30%: [PtzHudPanel] 平台控制指令实时解码 HUD
 *
 * 数据源:[AppUiState.deviceControl] StateFlow 写入,UI 订阅.
 *
 * 2026-06-26 PR-F T2:
 *   - MonitoringStage / overlays / StatusHeadline / CameraGlbView expect 拆到同包 4 个子文件
 *   - 主入口只剩 effect 路由 + Snackbar host + 全屏 flash
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

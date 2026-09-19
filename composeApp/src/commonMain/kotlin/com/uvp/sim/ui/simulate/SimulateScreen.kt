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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.floatingBottomBarReservedBottom
import com.uvp.sim.ui.model.DeviceEffectDto
import kotlinx.coroutines.delay

/**
 * "模拟"tab 主屏(M2 §4 设备控制 + 3D 模拟中心).
 *
 * 布局:
 * - 上 70%: [MonitoringStage] 3D 摄像机模型(Android: Filament; iOS: SceneKit;
 *   Desktop: 占位) + 各种只读 overlay
 * - 下 30%: [PtzHudPanel] 平台控制指令 HUD —— 云台页是本机控制台(方盘 + 参数 +
 *   预置位/看守位),其余三页是平台指令回放
 *
 * 数据源:[AppUiState.deviceControl] StateFlow 写入,UI 订阅.
 *
 * 2026-06-26 PR-F T2:
 *   - MonitoringStage / overlays / StatusHeadline / CameraGlbView expect 拆到同包 4 个子文件
 *   - 主入口只剩 effect 路由 + Snackbar host + 全屏 flash
 * 2026-09-16:本机 PTZ 手操几经搬迁(画布底部横条 → 画布下方独立行),最终收进
 *   HUD 云台页做成方盘控制台;回调由此处直接转交 [PtzHudPanel]。
 */
@Composable
fun SimulateScreen(state: AppUiState, actions: AppActions, modifier: Modifier = Modifier) {
    val deviceControl = state.deviceControl

    // SnapshotFlash 全屏快门白光
    val snapshotFlashAlpha = remember { Animatable(0f) }
    // ConfigChanged / DeviceUpgrade / FormatSDCard 三类 snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // 渲染类 effect 由 CameraGlbView 内部消费，其余类型在本层反馈。
    LaunchedEffect(deviceControl.pendingEffect) {
        when (val e = deviceControl.pendingEffect) {
            is DeviceEffectDto.SnapshotFlash -> {
                snapshotFlashAlpha.snapTo(0.85f)
                delay(80)
                snapshotFlashAlpha.animateTo(0f, animationSpec = tween(80))
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
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = 10.dp,
                    // 悬浮 tab bar 底部预留,让 PtzHudPanel 不被遮 —— iOS=130dp/其他=0dp
                    bottom = 10.dp + floatingBottomBarReservedBottom
                )
        ) {
            MonitoringStage(
                state = deviceControl,
                onPoseTick = actions::onPoseTick,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            PtzHudPanel(
                state = deviceControl,
                onLocalPtzAdjust = actions::onLocalPtzAdjust,
                onLocalLensAdjust = actions::onLocalLensAdjust,
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

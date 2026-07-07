package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.simulate.scenekit.SceneKitCameraScene
import com.uvp.sim.ui.simulate.scenekit.SceneKitEffectDispatcher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.SceneKit.SCNAntialiasingMode
import platform.SceneKit.SCNView

/**
 * iOS v1.3-C · CameraGlbView actual — SceneKit UIKitView 桥接.
 *
 * 从 v1.2 Text 占位改为真实 3D 视图:
 * - `UIKitView { SCNView }` 挂主线程 SceneKit renderer
 * - `remember { SceneKitCameraScene() }` 让 Compose recompose 时保持实例稳定
 * - `LaunchedEffect(pendingEffect)` 消费 [com.uvp.sim.ui.model.DeviceEffectDto]
 *   6 类 3D 层 effect,3 类委托 commonMain overlay 消费
 * - `LaunchedEffect(pan/tilt/zoom)` 平台连发时的姿态实时同步
 * - `LaunchedEffect(isRecording)` REC 红点 syncRecordingDot
 * - `DisposableEffect(Unit) { onDispose { detach } }` — SCNView pause + scene 释放
 *
 * 上游 spec: `~/Documents/Atlas/wiki/projects/uvp-gb28181-sim/specs/ios-v1.3-c-scenekit.md`
 * 上游 plan: `~/Documents/Atlas/wiki/projects/uvp-gb28181-sim/plans/ios-v1.3-c-scenekit.md`
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraGlbView(
    state: DeviceControlDto,
    onPoseTick: (Float, Float, Float) -> Unit,
    modifier: Modifier
) {
    val scene = remember { SceneKitCameraScene() }
    val dispatcher = remember(scene) { SceneKitEffectDispatcher(scene) }

    Box(modifier = modifier.fillMaxSize().background(UvpColor.PrimaryLight)) {
        UIKitView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val view = SCNView(frame = CGRectZero.readValue()).apply {
                    preferredFramesPerSecond = 60
                    antialiasingMode = SCNAntialiasingMode.SCNAntialiasingModeMultisampling2X
                    autoenablesDefaultLighting = false
                    backgroundColor = platform.UIKit.UIColor.clearColor
                    allowsCameraControl = false  // 3D 演示只跟平台命令走, 禁止手势
                }
                // 一次性 load + bind + attach
                scene.loadFromBundle()
                scene.bindPivots()
                scene.setupLightsAndCamera()
                scene.attach(view)
                view
            },
            update = { view ->
                // 每次 recompose 若姿态更新,由下面 LaunchedEffect 处理,这里不额外动
                if (scene.scnView == null) {
                    scene.attach(view)
                }
            }
        )
    }

    // T-C2-3: PTZ 姿态实时同步(平台按住键连发场景)
    LaunchedEffect(state.panAngle, state.tiltAngle, state.zoomLevel, state.panSpeed) {
        val duration = if (state.panSpeed > 0f || state.tiltSpeed > 0f || state.zoomSpeed > 0f) {
            // 平台连发, 用速度映射时长
            val speedByte = state.panSpeed.coerceAtLeast(state.tiltSpeed)
                .coerceAtLeast(state.zoomSpeed)
                .times(255f).toInt().coerceIn(1, 255)
            dispatcher.mapSpeedToDuration(speedByte)
        } else {
            0.2  // 默认 200ms
        }
        dispatcher.syncPtz(state.panAngle, state.tiltAngle, state.zoomLevel, duration)
        // 回写平台端知道当前姿态(节流由平台侧做)
        onPoseTick(state.panAngle, state.tiltAngle, state.zoomLevel)
    }

    // T-C3-1..5: effect 消费(pendingEffect 变化时 dispatch)
    LaunchedEffect(state.pendingEffect) {
        val effect = state.pendingEffect ?: return@LaunchedEffect
        dispatcher.dispatch(effect)
    }

    // T-C3-5: REC 红点
    LaunchedEffect(state.isRecording) {
        dispatcher.syncRecordingDot(state.isRecording)
    }

    // T-C1-5: 释放路径
    DisposableEffect(Unit) {
        onDispose {
            dispatcher.stopAllActions()
            scene.detach()
        }
    }
}

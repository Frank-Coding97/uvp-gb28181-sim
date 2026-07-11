package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import com.uvp.sim.filament.UVPFilamentView
import com.uvp.sim.ui.model.DeviceControlDto
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSBundle
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

/**
 * iOS Filament/Metal · CameraGlbView actual.
 *
 * 原生 UIView 直接加载 Android 同源 `security_camera.glb`，由 Filament Metal backend 渲染。
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
    var nativeView by remember { mutableStateOf<UVPFilamentView?>(null) }
    var sceneReady by remember { mutableStateOf(false) }
    val currentView by rememberUpdatedState(nativeView)
    val currentState by rememberUpdatedState(state)
    val currentPoseTick by rememberUpdatedState(onPoseTick)
    val thumbnailImage = remember {
        NSBundle.mainBundle.pathForResource("ptz_scene_thumbnail", "png")
            ?.let { UIImage.imageWithContentsOfFile(it) }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF263238))) {
        UIKitView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val view = UVPFilamentView(frame = CGRectZero.readValue())
                nativeView = view
                sceneReady = true
                view
            },
            update = { view ->
                nativeView = view
            },
            interactive = false
        )

        thumbnailImage?.let { image ->
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 10.dp)
                    .size(136.dp, 78.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.88f))
                    .border(1.dp, Color(0xFFD7DEE5), RoundedCornerShape(10.dp))
                    .padding(6.dp)
            ) {
                UIKitView(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp)),
                    factory = {
                        UIImageView().apply {
                            contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                            clipsToBounds = true
                            this.image = image
                        }
                    },
                    update = {
                        it.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                        it.clipsToBounds = true
                        it.image = image
                    },
                    interactive = false
                )
            }
        }
    }

    // Keep PTZ speed integration and pose reporting on the same native display-link
    // as rendering, matching Android's frame-loop behavior during press-and-hold.
    LaunchedEffect(sceneReady) {
        if (!sceneReady) return@LaunchedEffect
        while (isActive) {
            val view = currentView
            if (view != null) {
                val snapshot = currentState
                view.setPanSpeed(snapshot.panSpeed, snapshot.tiltSpeed, snapshot.zoomSpeed)
                currentPoseTick(
                    view.currentPanAngle(),
                    view.currentTiltAngle(),
                    view.currentZoomLevel()
                )
            }
            delay(166)
        }
    }

    // T-C3-1..5: effect 消费(pendingEffect 变化时 dispatch)
    LaunchedEffect(sceneReady, state.pendingEffect) {
        if (!sceneReady) return@LaunchedEffect
        val view = nativeView ?: return@LaunchedEffect
        when (val effect = state.pendingEffect ?: return@LaunchedEffect) {
            is com.uvp.sim.ui.model.DeviceEffectDto.IFrameFlash -> view.triggerIFrameFlash()
            is com.uvp.sim.ui.model.DeviceEffectDto.SnapshotFlash -> view.triggerSnapshotFlash()
            is com.uvp.sim.ui.model.DeviceEffectDto.Reboot -> view.triggerRebootAnimation()
            is com.uvp.sim.ui.model.DeviceEffectDto.HomePositionReturn -> view.easeToPanAngle(
                effect.targetPose.pan, effect.targetPose.tilt, effect.targetPose.zoom, 1.2
            )
            is com.uvp.sim.ui.model.DeviceEffectDto.PresetRecall -> view.easeToPanAngle(
                effect.targetPose.pan, effect.targetPose.tilt, effect.targetPose.zoom, 1.2
            )
            is com.uvp.sim.ui.model.DeviceEffectDto.PrecisePoseGoto -> view.easeToPanAngle(
                effect.targetPose.pan, effect.targetPose.tilt, effect.targetPose.zoom, 1.2
            )
            is com.uvp.sim.ui.model.DeviceEffectDto.ConfigChanged,
            is com.uvp.sim.ui.model.DeviceEffectDto.DeviceUpgradeRequested,
            is com.uvp.sim.ui.model.DeviceEffectDto.FormatSDCardRequested -> Unit
        }
    }

    // T-C1-5: 释放路径
    DisposableEffect(Unit) {
        onDispose {
            nativeView?.stopRendering()
            nativeView = null
        }
    }
}

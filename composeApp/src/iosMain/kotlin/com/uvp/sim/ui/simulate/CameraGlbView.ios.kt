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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.uvp.sim.filament.UVPFilamentView
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.PtzPoseDto
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.Clock
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
 * 原生 UIView 直接加载 Android 同源 `security_camera.glb`,由 Filament Metal backend 渲染。
 * 右下角 PtzThumbnail 语义完全对齐 Android `CameraGlbView.android.kt`:
 *  - **zoom 只影响缩略图**,不影响 3D 相机(通过 setPanSpeed 恒传 zoomSpeed=0 隔离 native
 *    `_camera->setProjection(kDefaultFov / _zoom, ...)` 的 FOV 缩放路径)。Kotlin 层自己
 *    积分 zoom,并在 pendingEffect (HomePosition/Preset/PrecisePoseGoto) 时 ease 到 target。
 *  - **view 内容区严格 16:9** 匹配 ptz_scene_thumbnail.png (1600×900),AspectFill 无
 *    额外裁切,transform 平移到 clamp 边界时视觉刚好对齐 view 边缘,消除"边缘间隙"。
 *  - **pose** 每 16ms 从 native 采 pan/tilt(平台 PTZCmd 走 native 累积)+ Kotlin 层
 *    zoomLevel,合成 PtzPoseDto 驱动 `UIImageView.transform` (CGAffineTransform)。
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

    var pose by remember { mutableStateOf(PtzPoseDto(0f, 0f, 1f)) }
    // Kotlin 层维护的 zoomLevel — 独立于 native `_zoom`,不影响 3D 相机 FOV,只喂缩略图 + poseTick。
    var zoomLevel by remember { mutableStateOf(1f) }
    var zoomEaseActive by remember { mutableStateOf(false) }
    var zoomEaseFrom by remember { mutableStateOf(1f) }
    var zoomEaseTo by remember { mutableStateOf(1f) }
    var zoomEaseStartMs by remember { mutableStateOf(0L) }
    val zoomEaseDurationMs = 1200L

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
            PtzThumbnail(
                image = image,
                pose = pose,
                widthDp = ThumbnailWidthDp,
                heightDp = ThumbnailHeightDp,
                innerPadding = ThumbnailInnerPaddingDp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 10.dp)
            )
        }
    }

    // 高频循环 (16ms):Kotlin 层积分 zoom (受 zoomSpeed / easeTo 驱动) + 组装 pose。
    LaunchedEffect(sceneReady) {
        if (!sceneReady) return@LaunchedEffect
        var lastMs = 0L
        while (isActive) {
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val dt = if (lastMs == 0L) 0f else ((nowMs - lastMs) / 1000f).coerceAtMost(0.1f)
            lastMs = nowMs
            currentView?.let { view ->
                val nextZoom = when {
                    zoomEaseActive -> {
                        val progress = ((nowMs - zoomEaseStartMs).toFloat() / zoomEaseDurationMs.toFloat())
                            .coerceIn(0f, 1f)
                        val eased = 0.5f - 0.5f * cos(progress * PI.toFloat())
                        val v = zoomEaseFrom + (zoomEaseTo - zoomEaseFrom) * eased
                        if (progress >= 1f) {
                            zoomEaseActive = false
                            zoomEaseTo
                        } else v
                    }
                    else -> (zoomLevel + currentState.zoomSpeed * dt).coerceIn(1f, 16f)
                }
                zoomLevel = nextZoom
                pose = PtzPoseDto(
                    view.currentPanAngle(),
                    view.currentTiltAngle(),
                    nextZoom
                )
            }
            delay(16)
        }
    }

    // 166ms 节流循环:回写 speed 给 native (zoomSpeed 恒 0,避免 native 缩放 3D) + 回写 pose 给 AppEngine。
    LaunchedEffect(sceneReady) {
        if (!sceneReady) return@LaunchedEffect
        while (isActive) {
            val view = currentView
            if (view != null) {
                val snapshot = currentState
                view.setPanSpeed(snapshot.panSpeed, snapshot.tiltSpeed, 0f)
                currentPoseTick(
                    view.currentPanAngle(),
                    view.currentTiltAngle(),
                    zoomLevel
                )
            }
            delay(166)
        }
    }

    // T-C3-1..5: effect 消费 — HomePosition/Preset/PrecisePoseGoto 时,pan/tilt 交给 native easeTo,
    // zoom 恒传 1 给 native (3D 相机不缩放),Kotlin 层独立 ease zoom 到 target。
    LaunchedEffect(sceneReady, state.pendingEffect) {
        if (!sceneReady) return@LaunchedEffect
        val view = nativeView ?: return@LaunchedEffect
        when (val effect = state.pendingEffect ?: return@LaunchedEffect) {
            is com.uvp.sim.ui.model.DeviceEffectDto.IFrameFlash -> view.triggerIFrameFlash()
            is com.uvp.sim.ui.model.DeviceEffectDto.SnapshotFlash -> view.triggerSnapshotFlash()
            is com.uvp.sim.ui.model.DeviceEffectDto.Reboot -> view.triggerRebootAnimation()
            is com.uvp.sim.ui.model.DeviceEffectDto.HomePositionReturn -> {
                view.easeToPanAngle(effect.targetPose.pan, effect.targetPose.tilt, 1f, 1.2)
                startZoomEase(
                    from = zoomLevel,
                    to = effect.targetPose.zoom,
                    setFrom = { zoomEaseFrom = it },
                    setTo = { zoomEaseTo = it },
                    setStartMs = { zoomEaseStartMs = it },
                    setActive = { zoomEaseActive = it },
                )
            }
            is com.uvp.sim.ui.model.DeviceEffectDto.PresetRecall -> {
                view.easeToPanAngle(effect.targetPose.pan, effect.targetPose.tilt, 1f, 1.2)
                startZoomEase(
                    from = zoomLevel,
                    to = effect.targetPose.zoom,
                    setFrom = { zoomEaseFrom = it },
                    setTo = { zoomEaseTo = it },
                    setStartMs = { zoomEaseStartMs = it },
                    setActive = { zoomEaseActive = it },
                )
            }
            is com.uvp.sim.ui.model.DeviceEffectDto.PrecisePoseGoto -> {
                view.easeToPanAngle(effect.targetPose.pan, effect.targetPose.tilt, 1f, 1.2)
                startZoomEase(
                    from = zoomLevel,
                    to = effect.targetPose.zoom,
                    setFrom = { zoomEaseFrom = it },
                    setTo = { zoomEaseTo = it },
                    setStartMs = { zoomEaseStartMs = it },
                    setActive = { zoomEaseActive = it },
                )
            }
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

private fun startZoomEase(
    from: Float,
    to: Float,
    setFrom: (Float) -> Unit,
    setTo: (Float) -> Unit,
    setStartMs: (Long) -> Unit,
    setActive: (Boolean) -> Unit,
) {
    setFrom(from)
    setTo(to.coerceIn(1f, 16f))
    setStartMs(Clock.System.now().toEpochMilliseconds())
    setActive(true)
}

// 缩略图外框 136×78,内 padding 2dp → 内容区 132×74 ≈ 16:9 (差 0.34%,匹配图 1600×900),
// AspectFill 无额外裁切,transform 平移到 clamp 边界视觉刚好对齐 view 边缘。
private val ThumbnailWidthDp = 136.dp
private val ThumbnailHeightDp = 78.dp
private val ThumbnailInnerPaddingDp = 2.dp

/**
 * 右下角 PTZ 缩略图 — 算法跟 Android `CameraGlbView.android.kt` 的 `PtzThumbnail` 完全一致:
 *   scale = (1.16 + (zoom-1)*0.08) clamp [1.16, 1.55]
 *   x = -pan/180 * maxX * 1.6  clamp
 *   y =  tilt/90 * maxY * 1.6  clamp
 *
 * iOS 用嵌套 UIView 直接控制 imageView.frame,而非 `UIView.transform` — 后者在 CMP UIKitView
 * hosting 下有 subtle 布局偏差(2026-07-13 老板真机右侧仍露 gap),用 frame 直接放到 layout
 * 空间更可靠。imageView contentMode = ScaleAspectFill 会按 imageView.frame 缩图,
 * container.clipsToBounds 裁到 view 边界。
 *
 * 数学:imageView.w = viewW * scale, x = (viewW - w)/2 + tx,tx ∈ [-maxX, maxX] 保证任何 tx
 * 都让 imageView 至少覆盖 container 一侧到另一侧,无 gap。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun PtzThumbnail(
    image: UIImage,
    pose: PtzPoseDto,
    widthDp: Dp,
    heightDp: Dp,
    innerPadding: Dp,
    modifier: Modifier = Modifier
) {
    val innerWidthPt = (widthDp - innerPadding * 2).value.toDouble()
    val innerHeightPt = (heightDp - innerPadding * 2).value.toDouble()
    val scale = (1.16f + (pose.zoom - 1f) * 0.08f).coerceIn(1.16f, 1.55f).toDouble()
    val maxX = innerWidthPt * (scale - 1.0) / 2.0
    val maxY = innerHeightPt * (scale - 1.0) / 2.0
    val tx = (-pose.pan.toDouble() / 180.0 * maxX * 1.6).coerceIn(-maxX, maxX)
    val ty = (pose.tilt.toDouble() / 90.0 * maxY * 1.6).coerceIn(-maxY, maxY)
    val imgW = innerWidthPt * scale
    val imgH = innerHeightPt * scale
    val imgX = (innerWidthPt - imgW) / 2.0 + tx
    val imgY = (innerHeightPt - imgH) / 2.0 + ty

    Box(
        modifier = modifier
            .size(widthDp, heightDp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.88f))
            .border(1.dp, Color(0xFFD7DEE5), RoundedCornerShape(10.dp))
            .padding(innerPadding)
    ) {
        UIKitView(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
            factory = {
                val container = platform.UIKit.UIView(frame = CGRectZero.readValue()).apply {
                    clipsToBounds = true
                    userInteractionEnabled = false
                }
                val imageView = UIImageView().apply {
                    contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                    clipsToBounds = true
                    this.image = image
                }
                container.addSubview(imageView)
                container
            },
            update = { container ->
                val imageView = container.subviews.firstOrNull() as? UIImageView ?: return@UIKitView
                if (imageView.image !== image) imageView.image = image
                imageView.setFrame(
                    platform.CoreGraphics.CGRectMake(imgX, imgY, imgW, imgH)
                )
            },
            interactive = false
        )
    }
}

package com.uvp.sim.ui

import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Android 屏幕摄像头预览 — P0-PREVIEW(2026-06-14)起改走 SurfaceView。
 *
 * 跟工业 IPC 同构:屏幕看到的画面来自 OsdRendererHolder 单一画面源,
 * 跟直播/录像同源(都带 OSD)。streamer 通过 [CameraPreviewBinder] 拿 SurfaceView 引用,
 * 监听 SurfaceHolder lifecycle,把 surface 注册给 OsdRenderer.setScreenSurface。
 */
@Composable
actual fun PlatformCameraPreview(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
        },
        update = { view ->
            CameraPreviewBinder.attach(view)
        }
    )
    DisposableEffect(Unit) {
        onDispose { CameraPreviewBinder.detach() }
    }
}

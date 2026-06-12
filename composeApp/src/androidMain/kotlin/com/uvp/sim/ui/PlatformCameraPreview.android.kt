package com.uvp.sim.ui

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun PlatformCameraPreview(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

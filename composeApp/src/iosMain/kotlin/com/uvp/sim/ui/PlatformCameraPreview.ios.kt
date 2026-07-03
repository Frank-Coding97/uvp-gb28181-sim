package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.uvp.sim.camera.IosCameraController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView

/**
 * iOS 相机预览 — 嵌 AVCaptureVideoPreviewLayer 到 Compose UIKitView。
 *
 * 订阅 [IosCameraSessionHolder.session]:session 就位时预览上画,
 * session 为 null 时显示空黑 UIView(AVCaptureSession 还没建)。
 *
 * PreviewLayer frame 通过自定义 UIView 子类的 layoutSubviews 同步,
 * 不依赖 Compose 的 recomposition 时机。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformCameraPreview(modifier: Modifier) {
    // v1.3-A T-P1-2: 消费点从 IosCameraSessionHolder.session 迁移到 IosCameraController.session。
    // v1.2 IosCameraStreamer 路径仍然通过 IosCameraSessionHolder.publish → controller.publishExternalSession
    // 反向 mirror 保持兼容,P6-1 清理 stream() 后 holder 可整体删除。
    val session by IosCameraController.session.collectAsState()

    UIKitView(
        factory = {
            val container = CameraPreviewContainerView()
            container.previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
            container.previewLayer.session = session
            container
        },
        modifier = modifier,
        update = { view ->
            view.previewLayer.session = session
        },
    )
}

/**
 * 自定义 UIView:持有 [AVCaptureVideoPreviewLayer] 作为唯一 sublayer,
 * 在 [layoutSubviews] 里同步 layer frame = bounds,确保旋转/resize 正确。
 */
@OptIn(ExperimentalForeignApi::class)
private class CameraPreviewContainerView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val previewLayer = AVCaptureVideoPreviewLayer()

    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.setFrame(bounds)
    }
}

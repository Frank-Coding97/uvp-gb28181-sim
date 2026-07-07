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
            // 关键:不在 factory 里挂 session。AVCaptureVideoPreviewLayer 尚未加入 window 时
            // attach 到 running AVCaptureSession 会同步阻塞主线程 ~9 秒(iOS 实测,2026-07-07)。
            // Session 挂载留给 update — 那时 layer 已进入 UIView 树,setter 是几百 ms 级别。
            val container = CameraPreviewContainerView()
            container.previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
            container
        },
        modifier = modifier,
        update = { view ->
            // 引用比较:同一 session 反复 setter 会触发 AVCaptureConnection 重建,避免。
            if (view.previewLayer.session === session) return@UIKitView
            view.previewLayer.session = session
        },
        onRelease = { view ->
            // 关键:UIView 销毁前显式解绑 session,否则 running session 上会累积残留 preview layer,
            // AVFoundation 资源用尽后 app 卡死(实测连续切 3 次即触发)。
            view.previewLayer.session = null
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

package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.uvp.sim.camera.IosCameraController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView

/**
 * iOS 相机预览 — 单例 UIView 宿主模型 (2026-07-07 重构)。
 *
 * 架构:preview UIView + AVCaptureVideoPreviewLayer 是 pipeline 的一部分,不是 UI 层的。
 * 生命周期跟 [IosCameraController.session] 对齐,不跟 Compose composable 走。
 *
 * 收益 (对比旧模型 "每次 factory 新建 container + update 挂 session"):
 * - 切 tab 单次成本:~300ms → ~0ms (UIKit 单亲约束下的 addSubview 转移是 O(1))
 * - session 首次挂 preview layer 的 ~300ms 只付一次,不再每次切 tab 重付
 * - session 上永远只挂一个 preview layer → 从根源消灭"连续切 3 次 app 卡死"
 *
 * 关键行为:
 * - [IosCameraPreviewHost.containerView] 是模块级单例,进程内唯一实例
 * - Session 挂载在 host init 里订阅 controller.session 一次到底,不依赖 UIKitView 生命周期
 * - [PlatformCameraPreview] 的 UIKitView.factory 每次返回同一 containerView;UIKit 单亲约束
 *   会自动把它从旧 hosting view detach、attach 到新的,preview layer 和 session 不动
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosCameraPreviewHost {
    /**
     * Preview UIView 单例。首次访问触发 [IosCameraPreviewHost] object 装载,
     * init 里启动 session collect 协程。
     */
    val containerView: CameraPreviewContainerView = CameraPreviewContainerView().apply {
        previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    // AVCaptureVideoPreviewLayer.session setter 要求主线程;collect 直接跑在 Main。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            IosCameraController.session.collect { session ->
                if (containerView.previewLayer.session !== session) {
                    containerView.previewLayer.session = session
                    // 2026-07-09:sensor 原生 LandscapeRight,用户手机竖着看需要 preview
                    // layer 单独 rotate 到 Portrait。这个 connection 跟 sample delegate 的
                    // output connection 是独立的两条路,不影响推流方向(1280x720 landscape)。
                    containerView.previewLayer.connection?.let { conn ->
                        if (conn.isVideoOrientationSupported()) {
                            conn.setVideoOrientation(AVCaptureVideoOrientationPortrait)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformCameraPreview(modifier: Modifier) {
    val container = IosCameraPreviewHost.containerView
    UIKitView(
        factory = {
            // UIKit 单亲约束:一个 UIView 只能有一个 superview。旧 hosting view 若还在,
            // 显式让出;新 hosting view 会通过 addSubview 自动接管。
            container.removeFromSuperview()
            container.userInteractionEnabled = false
            container
        },
        modifier = modifier,
        interactive = false,
        // 无 onRelease:containerView 是单例,不能被 dispose;session 挂载不受 UIKitView 生命周期影响
    )
}

/**
 * 自定义 UIView:持有 [AVCaptureVideoPreviewLayer] 作为唯一 sublayer,
 * 在 [layoutSubviews] 里同步 layer frame = bounds,确保旋转/resize 正确。
 */
@OptIn(ExperimentalForeignApi::class)
internal class CameraPreviewContainerView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val previewLayer = AVCaptureVideoPreviewLayer()

    init {
        userInteractionEnabled = false
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.setFrame(bounds)
    }
}

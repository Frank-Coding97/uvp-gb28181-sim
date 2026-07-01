package com.uvp.sim.camera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.AVFoundation.AVCaptureSession

/**
 * 进程级桥 — shared/iosMain 的 [IosCameraStreamer] 建好 AVCaptureSession 后
 * publish 到这里,composeApp/iosMain 的 PlatformCameraPreview 通过 UIKitView
 * 挂 AVCaptureVideoPreviewLayer(session:) 显示预览。
 *
 * 镜像 Android 侧的 CameraPreviewBinder 设计:视图层跟采集层解耦,
 * 生命周期由采集层驱动 —— stream() 建 session 时 publish,
 * releaseInternal() 释放时 clear。
 *
 * 当前 [IosCameraStreamer] 尚未接上 AVCaptureSession(T4-follow-up),因此
 * session 会长期为 null,预览显示空 UIView。等 AVCapture 采集线落地时,
 * 只需在 wireCaptureSession()/releaseInternal() 里调 publish 就自动上画。
 */
@OptIn(ExperimentalForeignApi::class)
object IosCameraSessionHolder {
    private val _session = MutableStateFlow<AVCaptureSession?>(null)
    val session: StateFlow<AVCaptureSession?> = _session

    internal fun publish(session: AVCaptureSession?) {
        _session.value = session
    }
}

package com.uvp.sim.snapshot

/**
 * Android actual 占位 stub。T5 任务接入 CameraX `ImageCapture` UseCase 后改为真实实现:
 *   - 持 AndroidCameraStreamer 引用,从其 lifecycle scope 上 bind 的 ImageCapture UseCase 取
 *   - takeJpeg 用 suspendCoroutine 包 takePicture(executor, OnImageCapturedCallback)
 *   - planes[0].buffer.toByteArray() → 应用 imageInfo.rotationDegrees 写 EXIF Orientation
 *
 * 当前阶段(T3)只保 KMP 编译通过,M4 T5 上真链路。
 */
actual class SnapshotCapture actual constructor() {
    actual suspend fun takeJpeg(): ByteArray? = null
}

package com.uvp.sim.snapshot

/**
 * Single-frame JPEG capture, GB/T 28181-2022 §9.5 图像抓拍 实现端口。
 *
 * 跟 [com.uvp.sim.camera.CameraCapture] 故意分开两个 expect:
 *   - CameraCapture 服务直播链路(VideoCapture UseCase + encoder)
 *   - SnapshotCapture 服务单帧抓取(ImageCapture UseCase)
 *   两者在 Android 同 lifecycle 共存,但抽象不耦合。
 *
 * iOS / JVM 走 stub(返 null + log warn),M5+ 再补 iOS 真实现。
 */
expect class SnapshotCapture() {
    /** 抓一帧 JPEG。返回 null 表示失败(未注册 / 平台不支持 / 相机异常),原因走 SystemLogger。 */
    suspend fun takeJpeg(): ByteArray?
}

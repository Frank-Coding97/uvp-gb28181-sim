package com.uvp.sim.snapshot

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger

/**
 * Android `SnapshotCapture` —— 当前阶段(2026-06-17 隔夜实现)只完成"无人值守"骨架的占位。
 *
 * **真实链路(T5 待办,2026-06-18 真机日完成)**:
 *   1. 在 [com.uvp.sim.camera.AndroidCameraStreamer] bind preview/video UseCase 时
 *      一并 bind `androidx.camera.core.ImageCapture` UseCase 到同 lifecycle scope
 *   2. 把 ImageCapture 引用通过 [setStreamer] / setter 注入本类
 *   3. [takeJpeg] 用 `suspendCoroutine` 包 `imageCapture.takePicture(executor, OnImageCapturedCallback)`
 *   4. `onCaptureSuccess(image)`:取 `image.planes[0].buffer.toByteArray()`(JPEG 已是单平面)
 *      + 应用 `imageInfo.rotationDegrees` 写 EXIF Orientation(零像素旋转)
 *   5. `onError(exception)`:resume null + log warn
 *
 * 隔夜仅返 null,运行期表现为:平台下发 SnapShotConfig → 引擎触发抓拍 → takeJpeg null →
 * 整张被跳过(SnapshotProgress.CaptureSkipped)→ 无 PUT 无 NOTIFY,但平台依旧收到 SIP 200 OK。
 * 不影响 7.4 旧 SnapShotCmd 路径(走独立 reportSnapshot Alarm 通道)。
 */
actual class SnapshotCapture actual constructor() {

    actual suspend fun takeJpeg(): ByteArray? {
        SystemLogger.emit(
            LogLevel.Warning,
            LogTag.Media,
            "SnapshotCapture.android: takeJpeg 占位返 null(T5 真机日补 CameraX ImageCapture)"
        )
        return null
    }
}

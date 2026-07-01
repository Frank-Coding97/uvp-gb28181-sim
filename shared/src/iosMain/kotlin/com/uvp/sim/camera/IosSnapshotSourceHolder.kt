package com.uvp.sim.camera

import kotlin.concurrent.Volatile

/**
 * 进程级桥 — 让 shared/iosMain 的 SnapshotCapture 能拿到当前活跃的 [IosCameraStreamer],
 * 从而复用其 CVPixelBuffer 最新一帧做 JPEG 抓拍(不新增 AVCapturePhotoOutput,避免动 AVCaptureSession)。
 *
 * 镜像 [IosCameraSessionHolder] 的设计:视图/抓拍层跟采集层解耦,
 * 生命周期由采集层驱动 —— wireCaptureSession() 建 session 时 publish streamer 自身,
 * releaseInternal() 释放时 clear。
 *
 * 消费方 (SnapshotCapture):调用 [current] 取得当前 streamer,
 * 再调 `streamer.latestFramePixelBuffer()` 拿一帧带 retain 的 pixel buffer。
 * 未开流时 [current] 为 null,SnapshotCapture 返 null 表示 skip。
 */
object IosSnapshotSourceHolder {
    @Volatile
    private var _current: IosCameraStreamer? = null

    val current: IosCameraStreamer? get() = _current

    internal fun publish(streamer: IosCameraStreamer?) {
        _current = streamer
    }
}

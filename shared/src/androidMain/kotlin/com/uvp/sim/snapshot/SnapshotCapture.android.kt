package com.uvp.sim.snapshot

import com.uvp.sim.camera.AndroidCameraStreamer
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger

actual class SnapshotCapture actual constructor() {

    @Volatile
    private var streamer: AndroidCameraStreamer? = null

    fun setStreamer(streamer: AndroidCameraStreamer?) {
        this.streamer = streamer
    }

    actual suspend fun takeJpeg(): ByteArray? {
        val current = streamer
        if (current == null) {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "Android 抓拍失败: CameraStreamer 未装配")
            return null
        }
        return current.takeSnapshotJpeg()
    }
}

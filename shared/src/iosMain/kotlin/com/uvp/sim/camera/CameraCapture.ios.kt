package com.uvp.sim.camera

import com.uvp.sim.media.H264Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.concurrent.Volatile

/**
 * iOS implementation of [CameraCapture].
 *
 * Mirrors the Android pattern: by default returns an empty flow; the
 * platform shell can attach a [IosCameraStreamer] via [setStreamer] once it's
 * actually implemented (T13).
 */
actual class CameraCapture actual constructor(@Suppress("unused") private val config: CaptureConfig) {

    @Volatile
    private var streamer: IosCameraStreamer? = null

    fun setStreamer(streamer: IosCameraStreamer?) {
        this.streamer = streamer
    }

    actual fun start(): Flow<H264Frame> = streamer?.stream() ?: emptyFlow()

    actual suspend fun stop() {
        streamer?.stop()
    }

    actual fun requestKeyFrame() {
        streamer?.requestKeyFrame()
    }
}

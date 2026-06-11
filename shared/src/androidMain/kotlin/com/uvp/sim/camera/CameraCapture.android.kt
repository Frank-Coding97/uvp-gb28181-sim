package com.uvp.sim.camera

import com.uvp.sim.media.H264Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android implementation of [CameraCapture].
 *
 * The KMP `expect class CameraCapture(config)` only takes a [CaptureConfig].
 * To bind CameraX we need an Android Context + LifecycleOwner — those don't
 * belong in commonMain. The pattern we use:
 *   1. Construct CameraCapture(config) normally.
 *   2. Cast to this Android actual and call [setStreamer] with an
 *      [AndroidCameraStreamer] that holds the Context/LifecycleOwner refs.
 *   3. The engine collects start() as usual.
 *
 * If [setStreamer] was never called, [start] returns an empty Flow (no frames),
 * matching the iOS / JVM stub behaviour.
 */
actual class CameraCapture actual constructor(@Suppress("unused") private val config: CaptureConfig) {

    @Volatile
    private var streamer: AndroidCameraStreamer? = null

    fun setStreamer(streamer: AndroidCameraStreamer?) {
        this.streamer = streamer
    }

    actual fun start(): Flow<H264Frame> = streamer?.stream() ?: emptyFlow()

    actual suspend fun stop() {
        streamer?.stop()
    }
}

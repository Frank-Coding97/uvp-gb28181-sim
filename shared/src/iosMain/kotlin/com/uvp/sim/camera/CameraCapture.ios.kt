package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.media.H264Frame
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.concurrent.Volatile

/**
 * iOS implementation of [CameraCapture].
 *
 * Mirrors the Android pattern: by default returns an empty flow; the
 * platform shell can attach a [IosCameraStreamer] via [setStreamer] once
 * the real pipeline lands.
 */
actual class CameraCapture actual constructor(private val config: CaptureConfig) {

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

    @Volatile
    private var pendingFacing: CameraFacing = config.cameraFacing

    /**
     * iOS v1.1 only supports back camera (plan DEC-4). Front-camera requests
     * are recorded to [pendingFacing] but do not affect the running session,
     * and a warning is emitted so platform-side operators can see the ignored
     * command in the SIP log stream.
     *
     * Real dual-camera switching lands in v1.2 alongside [dual-camera-channel].
     */
    actual fun setFacing(facing: CameraFacing) {
        pendingFacing = facing
        if (facing != CameraFacing.BACK) {
            SystemLogger.emit(
                level = LogLevel.Warning,
                tag = LogTag.Media,
                message = "iOS v1.1 only supports back camera; ignoring switch to $facing",
                detail = "pending target recorded but not applied — v1.2 will honor via IosCameraStreamer"
            )
        }
    }
}

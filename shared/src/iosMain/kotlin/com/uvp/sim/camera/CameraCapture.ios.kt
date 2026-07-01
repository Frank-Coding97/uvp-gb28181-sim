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
 * v1.1 wiring: [start] instantiates a fresh [IosCameraStreamer] with the
 * configured [CaptureConfig] and returns its encoded-frame flow directly.
 * [setStreamer] remains as a legacy hook for tests that want to inject a
 * pre-built streamer; when set it overrides the auto-built one.
 */
actual class CameraCapture actual constructor(private val config: CaptureConfig) {

    @Volatile
    private var injectedStreamer: IosCameraStreamer? = null

    @Volatile
    private var activeStreamer: IosCameraStreamer? = null

    /**
     * Legacy hook — pre-T4-follow-up the platform shell built the streamer
     * externally and pushed it in. New code should just call [start]. Passing
     * null clears the injection so [start] falls back to the auto-built path.
     */
    fun setStreamer(streamer: IosCameraStreamer?) {
        this.injectedStreamer = streamer
    }

    actual fun start(): Flow<H264Frame> {
        val streamer = injectedStreamer ?: IosCameraStreamer(config)
        activeStreamer = streamer
        return streamer.stream()
    }

    actual suspend fun stop() {
        activeStreamer?.stop()
        activeStreamer = null
    }

    actual fun requestKeyFrame() {
        activeStreamer?.requestKeyFrame()
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

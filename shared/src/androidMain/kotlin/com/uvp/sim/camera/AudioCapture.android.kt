package com.uvp.sim.camera

import com.uvp.sim.media.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android implementation of [AudioCapture]. Like the camera path, the heavy
 * lifting lives in [AndroidAudioStreamer]; this class is the cross-platform
 * façade engines depend on.
 */
actual class AudioCapture actual constructor(@Suppress("unused") private val config: AudioCaptureConfig) {

    @Volatile
    private var streamer: AndroidAudioStreamer? = null

    fun setStreamer(streamer: AndroidAudioStreamer?) {
        this.streamer = streamer
    }

    actual fun start(): Flow<AudioFrame> = streamer?.stream() ?: emptyFlow()

    actual suspend fun stop() {
        streamer?.stop()
    }
}

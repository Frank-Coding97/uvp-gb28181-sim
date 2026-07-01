package com.uvp.sim.camera

import com.uvp.sim.media.AudioFrame
import kotlinx.coroutines.flow.Flow

/**
 * iOS actual for [AudioCapture]. Delegates to [IosAudioStreamer] mirroring
 * the Android `AndroidAudioStreamer` + `AudioCapture.android.kt` wrapper pattern.
 *
 * v1.1 skeleton: AVAudioEngine tap lands after T2 cinterop spike verifies
 * ObjC block bridging.
 */
actual class AudioCapture actual constructor(private val config: AudioCaptureConfig) {

    private val streamer: IosAudioStreamer = IosAudioStreamer(config)

    actual fun start(): Flow<AudioFrame> = streamer.stream()

    actual suspend fun stop() {
        streamer.stop()
    }
}

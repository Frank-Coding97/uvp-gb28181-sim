package com.uvp.sim.camera

import com.uvp.sim.media.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

actual class AudioCapture actual constructor(@Suppress("unused") private val config: AudioCaptureConfig) {
    actual fun start(): Flow<AudioFrame> = flow { /* no-op on iOS stub */ }
    actual suspend fun stop() { /* no-op */ }
}

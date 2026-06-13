package com.uvp.sim.camera

import com.uvp.sim.media.H264Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * JVM stub — used only for unit tests of the encoder logic in [splitAnnexBNals].
 * No real camera. The simulator app does not target standalone JVM.
 */
actual class CameraCapture actual constructor(private val config: CaptureConfig) {
    actual fun start(): Flow<H264Frame> = flow { /* no-op */ }
    actual suspend fun stop() { /* no-op */ }
    actual fun requestKeyFrame() { /* no-op */ }
}

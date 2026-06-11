package com.uvp.sim.camera

import com.uvp.sim.media.H264Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * iOS implementation of [CameraCapture].
 *
 * **M1 stub**: real AVCaptureSession + VideoToolbox integration is T12 in tasks/v1.md.
 * This file provides the actual stubs so the iOS framework compiles in M1.
 */
actual class CameraCapture actual constructor(private val config: CaptureConfig) {

    actual fun start(): Flow<H264Frame> = flow {
        // Will be implemented in T12 — for now we don't emit anything.
        // The KMP Flow stays alive, no frames produced.
    }

    actual suspend fun stop() {
        // No-op stub
    }
}

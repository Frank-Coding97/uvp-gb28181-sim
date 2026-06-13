package com.uvp.sim.camera

import com.uvp.sim.media.H264Frame
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * iOS camera + H.264 streamer.
 *
 * **M1 status**: skeleton only — provides the cross-platform shape so KMP
 * framework compiles. The real AVCaptureSession + VideoToolbox encoder
 * integration is T13 — that requires:
 *   - Xcode + iOS Simulator (none on this build machine)
 *   - cinterop bindings for VTCompressionSession output callback (ObjC
 *     blocks / kotlin lambda interop)
 *   - SPS / PPS extraction from CMVideoFormatDescription via cinterop
 *
 * The Android implementation (AndroidCameraStreamer) covers the M1 北极星
 * indicator. iOS will follow in T13 once we can verify on an iPhone.
 */
class IosCameraStreamer(@Suppress("UNUSED_PARAMETER") private val config: CaptureConfig) {

    fun stream(): Flow<H264Frame> = callbackFlow {
        // Will be implemented in T13 with real Mac/Xcode access.
        close(IllegalStateException("IosCameraStreamer not yet implemented (T13)"))
        awaitClose { }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun stop() {
        // no-op
    }

    fun requestKeyFrame() {
        // Will call VTCompressionSessionEncodeFrame with kVTEncodeFrameOptionKey_ForceKeyFrame
        // when the iOS streamer lands in T13.
    }
}

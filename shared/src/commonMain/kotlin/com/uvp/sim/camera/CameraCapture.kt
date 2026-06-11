package com.uvp.sim.camera

import com.uvp.sim.media.H264Frame
import kotlinx.coroutines.flow.Flow

/**
 * Cross-platform camera capture API.
 *
 * - Android implementation uses CameraX + MediaCodec hardware H.264 encoder.
 * - iOS implementation uses AVCaptureSession + VideoToolbox.
 * - JVM has no implementation (M1 doesn't ship a JVM target for the app shell).
 *
 * The output is a Flow of [H264Frame]s, each containing one or more NAL units in
 * Annex-B form (no start-code prefix on individual NALs — the muxer adds them).
 *
 * Lifecycle:
 *   1. `start()` — open camera + spin up encoder.
 *   2. Collect from the returned Flow.
 *   3. `stop()` — release resources.
 */
expect class CameraCapture(config: CaptureConfig) {
    fun start(): Flow<H264Frame>
    suspend fun stop()
}

/**
 * Configuration for camera + encoder.
 *
 * Defaults match GB28181 commonly-tested settings:
 *   - 1280x720 @ 25 fps
 *   - H.264 baseline-ish, 2 Mbps target
 *   - GOP = 25 frames (1 second @ 25fps) — meets GB §10.1.1.2 keyframe interval
 */
data class CaptureConfig(
    val widthPx: Int = 1280,
    val heightPx: Int = 720,
    val frameRate: Int = 25,
    val bitrateBps: Int = 2_000_000,
    val keyframeIntervalSeconds: Int = 1,
    val cameraFacing: CameraFacing = CameraFacing.BACK
)

enum class CameraFacing { FRONT, BACK }

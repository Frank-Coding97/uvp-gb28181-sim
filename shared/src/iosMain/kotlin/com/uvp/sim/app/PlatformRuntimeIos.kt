package com.uvp.sim.app

import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.RecordingProfile
import com.uvp.sim.recording.NoopRecordingService
import com.uvp.sim.recording.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS 占位运行时(Wave 4 PR-PLATFORM-RUNTIME)。
 *
 * 跟 [PlatformResourcesIos] 同风格 — v1 仅 Android,本类只让 commonMain 能编译过 iosX64 /
 * iosArm64。M1.1 接 iOS 媒体管线时:
 *   - buildCameraCapture 装 IosCameraStreamer(AVCaptureSession + VideoToolbox)
 *   - buildAudioCapture 装 IosAudioStreamer(AVAudioEngine)
 *   - buildRecordingService 接 AVAssetWriter
 *   - applyVideoConfig 同步刷新 AVCaptureDevice / VideoToolbox config
 *
 * 当前 stub 故意 throw,而非 silent no-op — 避免误用导致空 facade 静默丢帧。
 * AppEngine 在 iOS 当前只跑信令路径,不调媒体装配方法,所以 throw 不会真触发。
 */
class PlatformRuntimeIos : PlatformRuntime {

    override fun buildCameraCapture(config: CaptureConfig): CameraCapture {
        // v1.1 T4-follow-up: CameraCapture.start() now internally builds
        // IosCameraStreamer(config) → AVCaptureSession + VideoToolbox pipeline.
        return CameraCapture(config)
    }

    override fun buildAudioCapture(config: AudioCaptureConfig): AudioCapture {
        // T8-follow-up: IosAudioStreamer 已接 AVAudioEngine + installTapOnBus。
        return AudioCapture(config)
    }

    override fun buildRecordingService(
        scope: CoroutineScope,
        deviceIdSupplier: () -> String,
        encoderConfigSupplier: () -> RecordingEncoderConfig,
        osdConfigSupplier: () -> StateFlow<OsdConfig>,
        profileSupplier: () -> RecordingProfile,
    ): RecordingService {
        // TODO(v1.1): 接 AVAssetWriter。当前返回 Noop,让 AppEngine 装配链不挂。
        return NoopRecordingService
    }

    override fun applyVideoConfig(captureConfig: CaptureConfig, audioConfig: AudioCaptureConfig) {
        // TODO(v1.1): 真重建 / applyVideoConfig 链路。当前 no-op。
    }

    override suspend fun release() {
        // no-op
    }
}

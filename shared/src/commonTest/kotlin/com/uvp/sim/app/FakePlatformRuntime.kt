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
 * 单测用 [PlatformRuntime] fake(Wave 4 PR-PLATFORM-RUNTIME)。
 *
 * 跟 [FakePlatformResources] 同风格。所有 build* 方法记账,test 断言 AppEngine 调用次数 + 参数。
 *
 * 默认行为:
 *   - buildCameraCapture / buildAudioCapture:返回新构造的 facade(common expect 类无 streamer,
 *     start() 返回 emptyFlow,适合纯装配测试)
 *   - buildRecordingService:返回 NoopRecordingService(单例 — 多次调返回同一引用)
 *   - applyVideoConfig:仅记账,不触发任何重建
 */
internal class FakePlatformRuntime : PlatformRuntime {

    val builtCameras = mutableListOf<CaptureConfig>()
    val builtAudios = mutableListOf<AudioCaptureConfig>()
    val builtRecordings = mutableListOf<Unit>()
    val appliedVideoConfigs = mutableListOf<Pair<CaptureConfig, AudioCaptureConfig>>()
    var releaseCalls: Int = 0
        private set

    override fun buildCameraCapture(config: CaptureConfig): CameraCapture {
        builtCameras += config
        return CameraCapture(config)
    }

    override fun buildAudioCapture(config: AudioCaptureConfig): AudioCapture {
        builtAudios += config
        return AudioCapture(config)
    }

    override fun buildRecordingService(
        scope: CoroutineScope,
        deviceIdSupplier: () -> String,
        encoderConfigSupplier: () -> RecordingEncoderConfig,
        osdConfigSupplier: () -> StateFlow<OsdConfig>,
        profileSupplier: () -> RecordingProfile,
    ): RecordingService {
        builtRecordings += Unit
        return NoopRecordingService
    }

    override fun applyVideoConfig(captureConfig: CaptureConfig, audioConfig: AudioCaptureConfig) {
        appliedVideoConfigs += captureConfig to audioConfig
    }

    val builtLocationStartPoints = mutableListOf<com.uvp.sim.config.GeoPoint>()

    override fun buildLocationProvider(startPoint: com.uvp.sim.config.GeoPoint):
        com.uvp.sim.domain.location.LocationProvider {
        builtLocationStartPoints += startPoint
        return com.uvp.sim.domain.MockGpsSource(startPoint)
    }

    override suspend fun release() {
        releaseCalls += 1
    }
}

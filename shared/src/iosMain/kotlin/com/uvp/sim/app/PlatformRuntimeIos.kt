package com.uvp.sim.app

import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.RecordingProfile
import com.uvp.sim.recording.IosRecordingFrameBridge
import com.uvp.sim.recording.IosRecordingService
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
        // v1.1 T-recording: IosRecordingService(AVAssetWriter skeleton) 上线。
        // AVCaptureSession 单实例 + CMSampleBuffer 灌 writer 的 feed 链留 v1.2,
        // 当前 mp4 header 合法但没 sample —— 至少 UI 状态机 / 索引落盘 / 切片
        // 都跑通,不再是 Noop 静默丢帧。
        val service = IosRecordingService(
            scope = scope,
            deviceIdSupplier = deviceIdSupplier,
            encoderConfigSupplier = encoderConfigSupplier,
            osdConfigSupplier = osdConfigSupplier,
            profileSupplier = profileSupplier,
        )
        // T-B3-4:同一个 IosRecordingService 既是 IosVideoFrameSink 又是 IosAudioFrameSink,
        // publish 二参数把它同时挂到 bridge 的 video + audio 分派点上。
        IosRecordingFrameBridge.publish(video = service, audio = service)
        return service
    }

    override fun applyVideoConfig(captureConfig: CaptureConfig, audioConfig: AudioCaptureConfig) {
        // E2 wire(v1.1):
        //
        // iOS 端的 CameraCapture 是"每次 start() 内部 new IosCameraStreamer(config)"的
        // 一次性对象,没有对外的 streamer ref 给我 applyCaptureConfig(参考 Android 侧
        // `AndroidCameraStreamer.applyCaptureConfig` 内部真 rebuild)。
        //
        // 结果:iOS 端的"改分辨率 / 码率 / GOP 后真重建"依赖于 UI 层调 stop() → start()
        // 完整循环,而不是无缝 applyCaptureConfig。这跟 spec §Q2("闪一下能忍")一致 —— 用户
        // 改视频参数是低频动作,重连触发的重建就够用。
        //
        // v1.2 如果要做无缝 apply,得给 IosCameraStreamer 加 applyCaptureConfig(new) 方法,
        // 参照 Android 侧的 encoder.stop / new VT session / OSD reattach 三步。
        //
        // Audio 侧简单:AVAudioEngine 停旧 + 建新即可,但 iOS 端 AudioCapture 同样是 start()
        // 内部 new IosAudioStreamer,外部拿不到 streamer ref。留 stop→start 循环即可。
    }

    override suspend fun release() {
        // T-B3-4:release 时把 video + audio sink 都清 null,防止残留引用。
        IosRecordingFrameBridge.publish(video = null, audio = null)
        // iOS 端 CameraCapture / AudioCapture / IosRecordingService 自己 release
    }
}

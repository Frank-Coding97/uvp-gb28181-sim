package com.uvp.sim.app

import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.camera.IosCameraController
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

    private var cameraCaptureRef: CameraCapture? = null
    private var audioCaptureRef: AudioCapture? = null

    override fun buildCameraCapture(config: CaptureConfig): CameraCapture {
        return cameraCaptureRef ?: CameraCapture(config).also { cameraCaptureRef = it }
    }

    override fun buildAudioCapture(config: AudioCaptureConfig): AudioCapture {
        return audioCaptureRef ?: AudioCapture(config).also { audioCaptureRef = it }
    }

    override fun buildRecordingService(
        scope: CoroutineScope,
        deviceIdSupplier: () -> String,
        encoderConfigSupplier: () -> RecordingEncoderConfig,
        osdConfigSupplier: () -> StateFlow<OsdConfig>,
        profileSupplier: () -> RecordingProfile,
    ): RecordingService {
        val osdConfigFlow = osdConfigSupplier()
        IosCameraController.installOsdConfigFlow(osdConfigFlow)
        // v1.1 T-recording: IosRecordingService(AVAssetWriter skeleton) 上线。
        // AVCaptureSession 单实例 + CMSampleBuffer 灌 writer 的 feed 链留 v1.2,
        // 当前 mp4 header 合法但没 sample —— 至少 UI 状态机 / 索引落盘 / 切片
        // 都跑通,不再是 Noop 静默丢帧。
        val service = IosRecordingService(
            scope = scope,
            deviceIdSupplier = deviceIdSupplier,
            encoderConfigSupplier = encoderConfigSupplier,
            osdConfigSupplier = { osdConfigFlow },
            profileSupplier = profileSupplier,
        )
        // T-B3-4:同一个 IosRecordingService 既是 IosVideoFrameSink 又是 IosAudioFrameSink,
        // publish 二参数把它同时挂到 bridge 的 video + audio 分派点上。
        IosRecordingFrameBridge.publish(video = service, audio = service)
        return service
    }

    /**
     * v1.3 起真实生效:
     *   - [CameraCapture.applyConfig] 同步刷新 facade + 调 [IosCameraController.applyRuntimeConfig]
     *     → 若 preview 在跑:sessionQueue 上换 preset / 帧率 / 输入设备,并按需重建 VT encoder
     *   - [AudioCapture.applyConfig] 先 stopSync 旧 [IosAudioStreamer](避免 AVAudioEngine 泄漏)
     *     再构造新实例,下一次 start() 用新 codec / sampleRate
     */
    override fun applyVideoConfig(captureConfig: CaptureConfig, audioConfig: AudioCaptureConfig) {
        cameraCaptureRef?.applyConfig(captureConfig)
        audioCaptureRef?.applyConfig(audioConfig)
    }

    override suspend fun release() {
        // callbackFlow 的 awaitClose 不一定已完成；显式 stop 归还 AVAudioEngine
        // 和共享 AVAudioSession lease，再清理 video + audio sink 引用。
        runCatching { audioCaptureRef?.stop() }
        IosRecordingFrameBridge.publish(video = null, audio = null)
        cameraCaptureRef = null
        audioCaptureRef = null
    }
}

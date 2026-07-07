package com.uvp.sim.app

import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.RecordingProfile
import com.uvp.sim.recording.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * 平台运行时装配契约(Wave 4 PR-PLATFORM-RUNTIME)。
 *
 * 跟 [PlatformResources] 并列 — Resources 暴露"纯资源 / 工厂"(RTP / snapshot / configStore /
 * localIp 等),Runtime 收口"媒体装配"(摄像头 / 音频 / 录像)。把原本散在 Activity
 * 的 9 参 `new AndroidCameraStreamer / new AndroidAudioStreamer / new AndroidRecordingService`
 * 装配下沉到平台层,Android 实现持 Context / Executor / lifecycle 等平台细节,
 * iOS 实现 v1.1 接入(当前阶段 stub)。
 *
 * 装配链:
 *   - MainActivity / iOS 壳:`PlatformRuntimeAndroid(applicationContext, ...)` 或 `PlatformRuntimeIos()`
 *   - 注入 [AppEngine] 一并跟 [PlatformResources] 传入
 *   - AppEngine 在装配段 `buildCoordinators` 调 [buildCameraCapture] / [buildAudioCapture] /
 *     [buildRecordingService] 拿到媒体对象,装到 InviteCoordinator / PlaybackCoordinator / ManscdpRouter
 *
 * 单例所有权(P3-3 顺手统一):
 *   - sStreamer / sRecordingService 这种进程级单例从 MainActivity companion 挪到
 *     [PlatformRuntimeAndroid] 内部,平台壳不再持媒体生命周期
 *
 * Video config bump(PR-USER-BUG-1):
 *   - 用户改了分辨率 / 帧率 / 码率 / 编解码器 → AppEngine 检测到 video config 变化 → 调
 *     [applyVideoConfig] → 实现真重建 streamer 或同值 short-circuit
 *
 * 接口形态:用 interface 而非 expect class — 让 commonTest 的 FakePlatformRuntime 直接 implement,
 * 不必为 KMP target 各写一份 actual stub。Android/iOS/JVM 各持自己的 concrete impl。
 */
interface PlatformRuntime {

    /**
     * 装配跨平台 [CameraCapture] facade。
     * Android 内部把 [com.uvp.sim.camera.AndroidCameraStreamer] 装到返回的 [CameraCapture] 上;
     * iOS 当前 stub(返回空 CameraCapture,无 streamer 装配)。
     *
     * 同一进程多次调用复用单例 streamer,只 applyCaptureConfig — 避免摄像头重开导致预览闪烁。
     */
    fun buildCameraCapture(config: CaptureConfig): CameraCapture

    /**
     * 装配跨平台 [AudioCapture] facade。
     * Android 装 [com.uvp.sim.camera.AndroidAudioStreamer];iOS stub。
     *
     * 跟 camera 不同:audio streamer 没有跨重建复用语义(stop+new 简单),所以这里
     * 每次都 new 一个,旧的 stop 兜底由 [applyVideoConfig] 内部处理。
     */
    fun buildAudioCapture(config: AudioCaptureConfig): AudioCapture

    /**
     * 装配跨平台 [RecordingService]。Android 实现是 AndroidRecordingService(共享 CameraX provider),
     * iOS 当前返回 NoopRecordingService。
     *
     * 4 个 supplier 跟随 SimConfig 实时刷新 — 用户改了 deviceId / 分辨率 / OSD / 录像 profile
     * 不需要重建 service。
     */
    fun buildRecordingService(
        scope: CoroutineScope,
        deviceIdSupplier: () -> String,
        encoderConfigSupplier: () -> RecordingEncoderConfig,
        osdConfigSupplier: () -> StateFlow<OsdConfig>,
        profileSupplier: () -> RecordingProfile,
    ): RecordingService

    /**
     * 视频配置变更入口(PR-USER-BUG-1 真重建逻辑下沉)。
     *
     * Android 实现:
     *   - 若 captureConfig 跟旧值不同 → release 旧 streamer + 装配新 streamer,并把
     *     当前 [CameraCapture] facade 重绑到新 streamer
     *   - 若同值 → applyCaptureConfig 走 short-circuit 不抖
     *   - audio streamer 走 stop + 新建 + setStreamer 链路
     *
     * iOS 当前 no-op(媒体管线 v1.1)。
     */
    fun applyVideoConfig(captureConfig: CaptureConfig, audioConfig: AudioCaptureConfig)

    /**
     * 释放进程级媒体单例。仅 ViewModel.onCleared / 进程退出时调。
     * Android 释放 sStreamer + sRecordingService 引用,iOS no-op。
     *
     * P1-2(2026-06-28):改为 suspend — 内部用结构化协程等 audio streamer.stop 完成,
     * 不再依赖 GlobalScope 兜底,调用方(ViewModel.onCleared)自带 timeout 控制 SLA。
     */
    suspend fun release()
}

/**
 * 录像 encoder 配置 — 跨平台共用结构。从 Android 端的 AndroidRecordingService.EncoderConfig
 * 抽出来挪到 commonMain,这样 PlatformRuntime 签名不带 Android 类型。
 */
data class RecordingEncoderConfig(
    val widthPx: Int,
    val heightPx: Int,
    val frameRate: Int,
    val bitrateBps: Int,
    val keyframeIntervalSeconds: Int,
    /**
     * T-B3-4:录像音轨 codec(跟随推流 codec)。null 表示无音轨(仅视频录像)。
     * iOS 侧在 start 时 snapshot 到 IosRecordingService.activeAudioCodec,openAudioInput
     * 按此构造 AVAssetWriterInput。Android 侧当前忽略(录像仅 mp4 video track)。
     */
    val audioCodec: com.uvp.sim.media.AudioCodec? = null,
)

package com.uvp.sim.app

import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.snapshot.JpegLocalCache
import com.uvp.sim.snapshot.SnapshotCapture
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/**
 * 平台资源装配契约(PR6 T6.2;Wave 3 P3-4 重命名 AndroidResources → PlatformResources)。
 *
 * 设计原则:本接口只放"完全平台决定"的资源 — 不持 scope / 不持 SimConfig,
 * AppEngine 在 connect 时基于本接口字段组装 RtpSender / Engine 等需要 scope 的工厂。
 *
 * Android `PlatformResourcesAndroid(context: Context, ...)` 实现 11 字段;
 * iOS `PlatformResourcesIos()` 全 null/no-op 占位(M1.1 接 iOS 实现)。
 */
interface PlatformResources {
    /** 摄像头实例 — Android 由 ViewModel/Activity 持生命周期,通过 lazy supplier 注入。 */
    val cameraCapture: CameraCapture?
    val audioCapture: AudioCapture?

    /**
     * RtpSender 构造工厂(参数:host / port / scope / mode)。
     * Android 直接 `RtpSender(host, port, scope, mode)`;iOS null。
     * scope 由 AppEngine.connect 时传入,所以接口里携带 scope 参数。
     */
    val rtpSenderFactory: ((host: String, port: Int, scope: CoroutineScope, mode: RtpMode) -> RtpSender)?

    /** 广播 RX source 工厂。 */
    val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)?

    /** 音频 sink 工厂(sampleRate, channels)。 */
    val audioSinkFactory: ((Int, Int) -> AudioSink)?

    /** 录像服务。iOS 占位返回 NoopRecordingService。 */
    val recordingService: RecordingService

    /**
     * PlaybackBuilder 工厂。Android 用 AndroidPlaybackBuilder。
     * scope + audioCodec + rtpSenderFactory 在 AppEngine.connect 时组合。
     */
    val playbackBuilderFactory: ((scope: CoroutineScope, audioCodec: com.uvp.sim.media.AudioCodec, rtpSenderFactory: (String, Int, RtpMode) -> RtpSender) -> com.uvp.sim.domain.PlaybackBuilder)?

    /** 本机 IP 提供器。 */
    val localIpProvider: () -> String

    /** 抓拍三件套(iOS 全 null,无 NOTIFY StoragePath)。 */
    val snapshotCapture: SnapshotCapture?
    val snapshotCache: JpegLocalCache?
    val httpEngineFactory: (() -> HttpClientEngine)?

    /** 持久化层。 */
    val configStore: ConfigStore
}

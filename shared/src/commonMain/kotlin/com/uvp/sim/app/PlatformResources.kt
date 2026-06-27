package com.uvp.sim.app

import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.snapshot.JpegLocalCache
import com.uvp.sim.snapshot.SnapshotCapture
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/**
 * 平台资源装配契约(PR6 T6.2;Wave 3 P3-4 重命名 AndroidResources → PlatformResources;
 * Wave 4 PR-PLATFORM-RUNTIME 把媒体三件套(camera/audio/recording)挪到 [PlatformRuntime])。
 *
 * 设计原则:本接口只放"完全平台决定的资源工厂" — 不持 scope / 不持 SimConfig / 不持媒体对象,
 * AppEngine 在 connect 时基于本接口字段组装 RtpSender / PlaybackBuilder 等需要 scope 的工厂,
 * 媒体对象走 [PlatformRuntime] 装配。
 *
 * Android `PlatformResourcesAndroid(context: Context, ...)` / iOS `PlatformResourcesIos()`。
 */
interface PlatformResources {
    /**
     * RtpSender 构造工厂(参数:host / port / scope / mode / expectedClientHost)。
     * Android 直接 `RtpSender(host, port, scope, mode, expectedClientHost)`;iOS null。
     * scope 由 AppEngine.connect 时传入,所以接口里携带 scope 参数。
     *
     * P1-5 (audit §2) expectedClientHost — TCP_PASSIVE 模式下源 IP 白名单,null = 不验。
     */
    val rtpSenderFactory: ((host: String, port: Int, scope: CoroutineScope, mode: RtpMode, expectedClientHost: String?) -> RtpSender)?

    /** 广播 RX source 工厂。 */
    val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)?

    /** 音频 sink 工厂(sampleRate, channels)。 */
    val audioSinkFactory: ((Int, Int) -> AudioSink)?

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

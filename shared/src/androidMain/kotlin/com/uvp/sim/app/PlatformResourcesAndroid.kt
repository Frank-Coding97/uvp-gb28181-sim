package com.uvp.sim.app

import android.content.Context
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.AndroidNetwork
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.snapshot.JpegLocalCache
import com.uvp.sim.snapshot.SnapshotCapture
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope

/**
 * Android 资源装配(PR6 T6.2;Wave 3 P3-4 重命名 AndroidResourcesAndroid → PlatformResourcesAndroid;
 * Wave 4 PR-PLATFORM-RUNTIME 把媒体三件套 supplier 收口到 [PlatformRuntimeAndroid])。
 *
 * 本类只负责"纯资源/工厂":RTP / 音频 sink / playback builder / snapshot / configStore /
 * localIp。媒体对象(camera/audio/recording)走 PlatformRuntime,不再过这里。
 */
class PlatformResourcesAndroid(
    private val context: Context,
    private val networkLocalIp: () -> String? = { null },
    configStoreOverride: ConfigStore? = null,
) : PlatformResources {

    override val rtpSenderFactory: ((String, Int, CoroutineScope, RtpMode, String?) -> RtpSender)? =
        { host, port, scope, mode, expectedClientHost -> RtpSender(host, port, scope, mode, expectedClientHost) }

    override val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)? =
        { scope -> com.uvp.sim.network.realBroadcastRxSource(scope) }

    override val audioSinkFactory: ((Int, Int) -> AudioSink)? =
        { sampleRate, channels -> com.uvp.sim.media.realAudioSink(sampleRate, channels) }

    override val playbackBuilderFactory: ((CoroutineScope, AudioCodec, (String, Int, RtpMode) -> RtpSender) -> com.uvp.sim.domain.PlaybackBuilder)? =
        { scope, audioCodec, rtpFactory ->
            com.uvp.sim.recording.AndroidPlaybackBuilder(
                scope = scope,
                rtpSenderFactory = rtpFactory,
                audioCodec = audioCodec,
            )
        }

    override val localIpProvider: () -> String = {
        networkLocalIp() ?: AndroidNetwork.activeIpv4(context) ?: "0.0.0.0"
    }

    override val snapshotCapture: SnapshotCapture? = SnapshotCapture()
    override val snapshotCache: JpegLocalCache? = JpegLocalCache.forContext(context)
    override val httpEngineFactory: (() -> HttpClientEngine)? = { CIO.create { requestTimeout = 30_000 } }

    override val configStore: ConfigStore = configStoreOverride ?: ConfigStoreAndroid(context)
}

package com.uvp.sim.app

import android.content.Context
import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.AndroidNetwork
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.snapshot.JpegLocalCache
import com.uvp.sim.snapshot.SnapshotCapture
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope

/**
 * Android 资源装配(PR6 T6.2)。
 *
 * 由 ViewModel 启动期构造,然后传给 AppEngine。
 * cameraCapture / audioCapture / recordingService 由 ViewModel/Activity 单独持(lifecycle 跟 view 绑),
 * 通过 supplier 注入,保持本类纯资源接口。
 */
class AndroidResourcesAndroid(
    private val context: Context,
    private val cameraSupplier: () -> CameraCapture? = { null },
    private val audioSupplier: () -> AudioCapture? = { null },
    private val recordingServiceSupplier: () -> RecordingService = { com.uvp.sim.recording.NoopRecordingService },
    private val networkLocalIp: () -> String? = { null },
) : AndroidResources {

    override val cameraCapture: CameraCapture? get() = cameraSupplier()
    override val audioCapture: AudioCapture? get() = audioSupplier()

    override val rtpSenderFactory: ((String, Int, CoroutineScope, RtpMode) -> RtpSender)? =
        { host, port, scope, mode -> RtpSender(host, port, scope, mode) }

    override val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)? =
        { scope -> com.uvp.sim.network.realBroadcastRxSource(scope) }

    override val audioSinkFactory: ((Int, Int) -> AudioSink)? =
        { sampleRate, channels -> com.uvp.sim.media.realAudioSink(sampleRate, channels) }

    override val recordingService: RecordingService get() = recordingServiceSupplier()

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

    override val configStore: ConfigStore = ConfigStoreAndroid(context)
}

package com.uvp.sim.app

import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.config.SimConfig
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.recording.NoopRecordingService
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.snapshot.JpegLocalCache
import com.uvp.sim.snapshot.SnapshotCapture
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/** 单测用 fake AndroidResources(全空)。 */
internal class FakeAndroidResources(
    override val cameraCapture: CameraCapture? = null,
    override val audioCapture: AudioCapture? = null,
    override val rtpSenderFactory: ((String, Int, CoroutineScope, RtpMode) -> RtpSender)? = null,
    override val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)? = null,
    override val audioSinkFactory: ((Int, Int) -> AudioSink)? = null,
    override val recordingService: RecordingService = NoopRecordingService,
    override val playbackBuilderFactory: ((CoroutineScope, AudioCodec, (String, Int, RtpMode) -> RtpSender) -> com.uvp.sim.domain.PlaybackBuilder)? = null,
    override val localIpProvider: () -> String = { "0.0.0.0" },
    override val snapshotCapture: SnapshotCapture? = null,
    override val snapshotCache: JpegLocalCache? = null,
    override val httpEngineFactory: (() -> HttpClientEngine)? = null,
    override val configStore: ConfigStore = FakeConfigStore(),
) : AndroidResources

internal class FakeConfigStore(
    private var stored: SimConfig? = null,
) : ConfigStore {
    override suspend fun loadOnce(fallback: SimConfig): SimConfig = stored ?: fallback
    override suspend fun save(config: SimConfig) { stored = config }
}

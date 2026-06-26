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

/**
 * iOS 占位资源(PR6 T6.2;Wave 3 P3-4 重命名 AndroidResourcesIos → PlatformResourcesIos)。
 *
 * 全部字段 null/no-op,M1.1 接入 iOS 实现:
 *   - cameraCapture / audioCapture:CameraCapture 已有 iOS expect/actual,但 PR6 仅占位
 *   - rtpSenderFactory:Darwin 框架原生 socket,M1.1 实现
 *   - snapshot 三件套:UIImage 编码 + NSURLSession 上传,M1.1 实现
 *   - configStore:NSUserDefaults,M1.1 实现
 *
 * 当前 PR 目标只是让 commonMain 能编译过 iosArm64 / iosX64,iOS 实际跑 PR6 不在范围。
 */
class PlatformResourcesIos : PlatformResources {
    override val cameraCapture: CameraCapture? = null
    override val audioCapture: AudioCapture? = null
    override val rtpSenderFactory: ((String, Int, CoroutineScope, RtpMode) -> RtpSender)? = null
    override val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)? = null
    override val audioSinkFactory: ((Int, Int) -> AudioSink)? = null
    override val recordingService: RecordingService = NoopRecordingService
    override val playbackBuilderFactory: ((CoroutineScope, AudioCodec, (String, Int, RtpMode) -> RtpSender) -> com.uvp.sim.domain.PlaybackBuilder)? = null
    override val localIpProvider: () -> String = { "0.0.0.0" }
    override val snapshotCapture: SnapshotCapture? = null
    override val snapshotCache: JpegLocalCache? = null
    override val httpEngineFactory: (() -> HttpClientEngine)? = null
    override val configStore: ConfigStore = ConfigStoreIos()
}

/** iOS 占位 ConfigStore — loadOnce 永远返回 fallback,save no-op。M1.1 接 NSUserDefaults。 */
class ConfigStoreIos : ConfigStore {
    override suspend fun loadOnce(fallback: SimConfig): SimConfig = fallback
    override suspend fun save(config: SimConfig) { /* no-op */ }
}

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
 * 平台资源装配契约(PR6 T6.2)。
 *
 * **设计调整**(plan §1 决策 4 → AndroidResources 改 interface):
 *   spec §1.1 锁定 ConfigStore 为 expect class(保留),AndroidResources 改 interface 更务实 ——
 *   - expect class 不允许 override 字段(commonTest fake 无法实现)
 *   - interface 让 commonTest 共享 fake / Android Activity 直接写 actual class / iOS 写占位 class
 *
 * Android `AndroidResourcesAndroid(application: Context)` 实现 9 字段,
 * iOS `AndroidResourcesIos()` 全 null/no-op 占位(M1.1 填)。
 *
 * Engine 之前接的 9 个 lambda 全部归集 + ConfigStore + Snapshot 三件套。
 */
interface AndroidResources {
    // 媒体能力(iOS 全 null,M1.1 填)
    val cameraCapture: CameraCapture?
    val audioCapture: AudioCapture?
    val rtpSenderFactory: ((host: String, port: Int, mode: RtpMode) -> RtpSender)?
    val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)?
    val audioSinkFactory: ((Int, Int) -> AudioSink)?

    /** 录像服务。iOS 占位返回 NoopRecordingService。 */
    val recordingService: RecordingService

    /** PlaybackBuilder 工厂(SimConfig.recording.playbackAudioCodec 在 connect 时才确定)。 */
    val playbackBuilderFactory: ((scope: CoroutineScope, audioCodec: com.uvp.sim.media.AudioCodec) -> com.uvp.sim.domain.PlaybackBuilder)?

    /** 本机 IP 提供器。 */
    val localIpProvider: () -> String

    /** 抓拍三件套。 */
    val snapshotCapture: SnapshotCapture?
    val snapshotCache: JpegLocalCache?
    val httpEngineFactory: (() -> HttpClientEngine)?

    /** 持久化层(详见 [ConfigStore] expect class)。 */
    val configStore: ConfigStore
}

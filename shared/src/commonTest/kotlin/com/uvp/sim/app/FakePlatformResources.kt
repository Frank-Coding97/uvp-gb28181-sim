package com.uvp.sim.app

import com.uvp.sim.config.SimConfig
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.snapshot.JpegLocalCache
import com.uvp.sim.snapshot.SnapshotCapture
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/** 单测用 fake PlatformResources(全空)。 */
internal class FakePlatformResources(
    override val rtpSenderFactory: ((String, Int, CoroutineScope, RtpMode, String?) -> RtpSender)? = null,
    override val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)? = null,
    override val audioSinkFactory: ((Int, Int) -> AudioSink)? = null,
    override val playbackBuilderFactory: ((CoroutineScope, AudioCodec, (String, Int, RtpMode) -> RtpSender) -> com.uvp.sim.domain.PlaybackBuilder)? = null,
    override val localIpProvider: () -> String = { "0.0.0.0" },
    override val snapshotCapture: SnapshotCapture? = null,
    override val snapshotCache: JpegLocalCache? = null,
    override val httpEngineFactory: (() -> HttpClientEngine)? = null,
    override val configStore: ConfigStore = FakeConfigStore(),
    override val deviceStateStore: DeviceStateStore = FakeDeviceStateStore(),
) : PlatformResources

internal class FakeConfigStore(
    private var stored: SimConfig? = null,
) : ConfigStore {
    override suspend fun loadOnce(fallback: SimConfig): SimConfig = stored ?: fallback
    override suspend fun save(config: SimConfig) { stored = config }
}

/**
 * 单测用内存存档。
 *
 * [saveCount] 存在是为了断言"**该不该**写盘"这类行为 —— 只断言"存进去了"会漏掉
 * "每帧都在写"这种性能问题,而 [DeviceStatePersister] 的核心契约恰恰是**不**频繁写。
 */
internal class FakeDeviceStateStore(
    private var stored: DeviceStateSnapshot? = null,
) : DeviceStateStore {
    var saveCount: Int = 0
        private set

    /** 非 null 时每次 [save] 都抛它 —— 模拟磁盘满 / 权限坏这类**持续**故障。 */
    var saveFailure: Throwable? = null

    override suspend fun loadOnce(): DeviceStateSnapshot? = stored

    override suspend fun save(snapshot: DeviceStateSnapshot) {
        saveFailure?.let { throw it }
        stored = snapshot
        saveCount++
    }

    /** 预置一份存档(模拟"上次运行留下的数据")。 */
    fun seed(snapshot: DeviceStateSnapshot) { stored = snapshot }

    /** 读当前存档内容(断言"存了什么")。 */
    fun peek(): DeviceStateSnapshot? = stored
}

package com.uvp.sim.app

import com.uvp.sim.config.SimConfig
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.IosLocalIpProvider
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.snapshot.JpegLocalCache
import com.uvp.sim.snapshot.SnapshotCapture
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSUserDefaults

/**
 * iOS 资源装配(v1.1 Wave 2 wire 收尾):从占位 null 转成跟 Android 对齐的 real factory。
 *
 * 各 factory 对应关系:
 *   - rtpSenderFactory:构造 [RtpSender.ios](Ktor Native UDP/TCP)
 *   - rtpReceiverFactory:走 commonMain `realBroadcastRxSource` → [RtpReceiver.ios]
 *   - audioSinkFactory:走 commonMain `realAudioSink` → [AudioPlayback.ios](AVAudioEngine)
 *   - playbackBuilderFactory:[IosPlaybackBuilder](镜像 Android,demuxFactory 用 IosMp4DemuxSource)
 *   - snapshotCapture / snapshotCache:v1.1 Wave 1 C 组落地
 *   - httpEngineFactory:Ktor Darwin engine
 *   - localIpProvider:v1.2 上 getifaddrs 报告的活跃网卡 IPv4,无值时兜底 0.0.0.0
 *   - configStore:NSUserDefaults(v1.1 A3 已落地,见下方 [ConfigStoreIos])
 */
class PlatformResourcesIos : PlatformResources {
    override val rtpSenderFactory: ((String, Int, CoroutineScope, RtpMode, String?) -> RtpSender)? =
        { host, port, scope, mode, expectedClientHost -> RtpSender(host, port, scope, mode, expectedClientHost) }

    override val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)? =
        { scope -> com.uvp.sim.network.realBroadcastRxSource(scope) }

    override val audioSinkFactory: ((Int, Int) -> AudioSink)? =
        { sampleRate, channels -> com.uvp.sim.media.realAudioSink(sampleRate, channels) }

    override val playbackBuilderFactory: ((CoroutineScope, AudioCodec, (String, Int, RtpMode) -> RtpSender) -> com.uvp.sim.domain.PlaybackBuilder)? =
        { scope, audioCodec, rtpFactory ->
            com.uvp.sim.recording.IosPlaybackBuilder(
                scope = scope,
                rtpSenderFactory = rtpFactory,
                audioCodec = audioCodec,
            )
        }

    override val localIpProvider: () -> String = { IosLocalIpProvider.currentActiveIp() ?: "0.0.0.0" }

    override val snapshotCapture: SnapshotCapture? = SnapshotCapture()
    override val snapshotCache: JpegLocalCache? = JpegLocalCache()
    override val httpEngineFactory: (() -> HttpClientEngine)? = { Darwin.create() }

    override val configStore: ConfigStore = ConfigStoreIos()
}

/**
 * iOS 实现:NSUserDefaults + JSON 序列化持久化(v1.1 A3),device.password 走 Keychain(v1.2 C3)。
 *
 * key `com.uvp.sim.config` 存 [SimConfig] 的 JSON,其中 device.password 持久化时清空。
 * 旧 JSON 明文 password 首次 load 后迁移到 Keychain 并重写 JSON 清理明文。
 */
class ConfigStoreIos(
    private val jsonStore: IosConfigJsonStore = UserDefaultsConfigJsonStore(),
    private val passwordStore: DevicePasswordStore = KeychainStore(),
) : ConfigStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun loadOnce(fallback: SimConfig): SimConfig {
        val raw = jsonStore.read() ?: return fallback
        val decoded = runCatching { json.decodeFromString<SimConfig>(raw) }.getOrElse { fallback }
        val account = KeychainStore.accountForDeviceId(decoded.device.deviceId)
        val keychainPassword = runCatching { passwordStore.read(account) }.getOrNull()
        val legacyPassword = decoded.device.password
        val restoredPassword = keychainPassword ?: legacyPassword
        val restored = decoded.copy(device = decoded.device.copy(password = restoredPassword))
        if (keychainPassword == null && legacyPassword.isNotEmpty()) {
            runCatching { passwordStore.save(account, legacyPassword) }
            saveSanitizedConfig(restored)
        } else if (legacyPassword.isNotEmpty()) {
            saveSanitizedConfig(restored)
        }
        return restored
    }

    override suspend fun save(config: SimConfig) {
        val account = KeychainStore.accountForDeviceId(config.device.deviceId)
        runCatching { passwordStore.save(account, config.device.password) }
        saveSanitizedConfig(config)
    }

    private fun saveSanitizedConfig(config: SimConfig) {
        val encoded = runCatching {
            json.encodeToString(config.copy(device = config.device.copy(password = "")))
        }.getOrNull() ?: return
        jsonStore.write(encoded)
    }
}

interface IosConfigJsonStore {
    fun read(): String?
    fun write(value: String)
}

class UserDefaultsConfigJsonStore : IosConfigJsonStore {
    override fun read(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY_CONFIG_JSON)

    override fun write(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, KEY_CONFIG_JSON)
        NSUserDefaults.standardUserDefaults.synchronize()
    }

    companion object {
        const val KEY_CONFIG_JSON = "com.uvp.sim.config"
    }
}

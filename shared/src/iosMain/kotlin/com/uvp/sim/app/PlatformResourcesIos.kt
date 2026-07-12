package com.uvp.sim.app

import com.uvp.sim.api.LogLevel
import com.uvp.sim.api.LogTag
import com.uvp.sim.config.SimConfig
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.IosLocalIpProvider
import com.uvp.sim.network.IosSigpipeGuard
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.observability.SystemLogger
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
    init {
        IosSigpipeGuard.install()
    }

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
        // 2026-07-03 诊断:冷启动读 Keychain 状态。看看 read 是拿到 null,还是拿到值。
        val status = (passwordStore as? KeychainStore)?.lastStatusForTest
        SystemLogger.emit(
            LogLevel.Info,
            LogTag.Resource,
            "Keychain load account=$account hasValue=${keychainPassword != null} " +
                "readStatus=$status legacyPasswordLen=${legacyPassword.length}"
        )
        val restoredPassword = keychainPassword ?: legacyPassword
        val restored = decoded.copy(device = decoded.device.copy(password = restoredPassword))
        if (keychainPassword == null && legacyPassword.isNotEmpty()) {
            // 迁移 legacy 明文 → Keychain。写成功才 sanitize JSON,写失败保留明文
            // (2026-07-03 真机验:Personal Team 签名下 Keychain 写会静默 return false)。
            val migrated = runCatching { passwordStore.save(account, legacyPassword) }.getOrDefault(false)
            if (migrated) {
                saveSanitizedConfig(restored)
            } else {
                logKeychainFailure("legacy-migration")
                // 保留 legacy 明文;下次 load 依然从 JSON 恢复。
            }
        } else if (legacyPassword.isNotEmpty()) {
            // Keychain 有值且 JSON 也存了明文 — 收敛到 sanitized 状态。
            saveSanitizedConfig(restored)
        }
        return restored
    }

    override suspend fun save(config: SimConfig) {
        val account = KeychainStore.accountForDeviceId(config.device.deviceId)
        val written = runCatching { passwordStore.save(account, config.device.password) }
            .getOrDefault(false)
        // 2026-07-03 诊断:无条件 emit 一次 Keychain 状态,真机拿到 status 码定位根因。
        logKeychainAttempt("save", account = account, written = written)
        if (written) {
            saveSanitizedConfig(config)
        } else {
            // Personal Team 签名下 Keychain 静默失败会被 runCatching 吞。
            // sanitize JSON 会导致冷启动后密码彻底丢失,退回 v1.1 行为(JSON 明文)。
            saveConfigWithPassword(config)
        }
    }

    private fun saveSanitizedConfig(config: SimConfig) {
        val encoded = runCatching {
            json.encodeToString(config.copy(device = config.device.copy(password = "")))
        }.getOrNull() ?: return
        jsonStore.write(encoded)
    }

    private fun saveConfigWithPassword(config: SimConfig) {
        val encoded = runCatching { json.encodeToString(config) }.getOrNull() ?: return
        jsonStore.write(encoded)
    }

    private fun logKeychainFailure(phase: String) {
        val status = (passwordStore as? KeychainStore)?.lastStatusForTest
        SystemLogger.emit(
            LogLevel.Warning,
            LogTag.Resource,
            "Keychain $phase failed status=$status — fell back to plaintext JSON"
        )
    }

    private fun logKeychainAttempt(phase: String, account: String, written: Boolean) {
        val status = (passwordStore as? KeychainStore)?.lastStatusForTest
        val level = if (written) LogLevel.Info else LogLevel.Warning
        SystemLogger.emit(
            level,
            LogTag.Resource,
            "Keychain $phase account=$account written=$written status=$status"
        )
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

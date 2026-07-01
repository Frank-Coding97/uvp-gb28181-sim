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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * iOS 占位资源(PR6 T6.2;Wave 3 P3-4 重命名 AndroidResourcesIos → PlatformResourcesIos;
 * Wave 4 PR-PLATFORM-RUNTIME 媒体三件套挪到 [PlatformRuntimeIos])。
 *
 * 全部字段 null/no-op,M1.1 接入 iOS 实现:
 *   - rtpSenderFactory:Darwin 框架原生 socket,M1.1 实现
 *   - snapshot 三件套:UIImage 编码 + NSURLSession 上传,M1.1 实现
 *   - configStore:NSUserDefaults(v1.1 A3 已落地,见下方 [ConfigStoreIos])
 */
class PlatformResourcesIos : PlatformResources {
    override val rtpSenderFactory: ((String, Int, CoroutineScope, RtpMode, String?) -> RtpSender)? = null
    override val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)? = null
    override val audioSinkFactory: ((Int, Int) -> AudioSink)? = null
    override val playbackBuilderFactory: ((CoroutineScope, AudioCodec, (String, Int, RtpMode) -> RtpSender) -> com.uvp.sim.domain.PlaybackBuilder)? = null
    override val localIpProvider: () -> String = { "0.0.0.0" }
    override val snapshotCapture: SnapshotCapture? = null
    override val snapshotCache: JpegLocalCache? = null
    override val httpEngineFactory: (() -> HttpClientEngine)? = null
    override val configStore: ConfigStore = ConfigStoreIos()
}

/**
 * iOS 实现:NSUserDefaults + JSON 序列化持久化(v1.1 A3)。
 *
 * key `com.uvp.sim.config` 存 [SimConfig] 的 JSON 明文。
 *
 * 与 Android 侧的差异(有意为之):
 *   - Android [com.uvp.sim.app.ConfigStoreAndroid] 用 AES-GCM + Android Keystore 加密
 *     device.password 字段,iOS 侧不做同等加密(NSUserDefaults 走沙箱内保护,
 *     root/JB 才可读;若后续要做等价保护应改用 Keychain,这里先满足
 *     "重启不丢配置"的最低要求)。
 *   - 不做老数据升级(iOS 侧无历史明文数据)。
 *   - decodeFromString 失败(schema 变化 / 数据损坏)静默回落 fallback。
 */
class ConfigStoreIos : ConfigStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun loadOnce(fallback: SimConfig): SimConfig {
        val raw = NSUserDefaults.standardUserDefaults.stringForKey(KEY_CONFIG_JSON)
            ?: return fallback
        return runCatching { json.decodeFromString<SimConfig>(raw) }.getOrElse { fallback }
    }

    override suspend fun save(config: SimConfig) {
        val encoded = runCatching { json.encodeToString(config) }.getOrNull() ?: return
        NSUserDefaults.standardUserDefaults.setObject(encoded, KEY_CONFIG_JSON)
        NSUserDefaults.standardUserDefaults.synchronize()
    }

    companion object {
        private const val KEY_CONFIG_JSON = "com.uvp.sim.config"
    }
}

package com.uvp.sim.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uvp.sim.config.SimConfig
import com.uvp.sim.observability.ErrorCategory
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android 实现:DataStore Preferences 持久化(从 androidApp/ConfigStore.kt 迁过来)。
 *
 * SharedPreferences key 保持 `sim_config_json`(用户已有数据兼容,无 schema 迁移)。
 *
 * P3-8 (second-round-security-review.md):device.password 加密存储。
 * 用 Android Keystore-backed AES-GCM 加密 password 字段。
 * 老数据(明文 password)首次读自动升级加密写回。Keystore 失败拒绝持久化。
 *
 * [keyProvider] 默认 [AndroidKeystoreKeyProvider] — 真实设备 / 真机测试用 Android Keystore;
 * Robolectric / 纯 JVM 单测可以注入 [InMemoryKeyProvider] 绕开 AndroidKeyStore。
 */
class ConfigStoreAndroid(
    private val context: Context,
    private val keyProvider: KeyProvider = AndroidKeystoreKeyProvider(KEYSTORE_ALIAS),
) : ConfigStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Lazy init SecretKey
    private val secretKey: SecretKey by lazy {
        keyProvider.getOrCreateKey()
    }

    override suspend fun loadOnce(fallback: SimConfig): SimConfig {
        val prefs = context.configDataStore.data.first()
        val raw = prefs[KEY_CONFIG_JSON] ?: return fallback
        val decrypted = try {
            decryptPasswordInJson(raw)
        } catch (e: Exception) {
            SystemLogger.emit(
                LogLevel.Error,
                com.uvp.sim.api.LogTag.Resource,
                "ConfigStoreAndroid.loadOnce 解密 password 失败,回退 fallback",
                e.stackTraceToString(),
                ErrorCategory.Internal
            )
            return fallback
        }
        val config = runCatching { json.decodeFromString<SimConfig>(decrypted) }.getOrDefault(fallback)

        // 老数据自动升级:检查原始 JSON 是否已加密,若否则写回 enc
        if (!raw.contains(ENC_PREFIX)) {
            SystemLogger.emit(
                LogLevel.Info,
                com.uvp.sim.api.LogTag.Resource,
                "ConfigStoreAndroid 检测到老数据(明文 password),自动升级加密写回"
            )
            save(config)
        }
        return config
    }

    override suspend fun save(config: SimConfig) {
        val plainJson = json.encodeToString(config)
        val encrypted = try {
            encryptPasswordInJson(plainJson)
        } catch (e: Exception) {
            SystemLogger.emit(
                LogLevel.Error,
                com.uvp.sim.api.LogTag.Resource,
                "ConfigStoreAndroid.save 加密 password 失败,拒绝持久化",
                e.stackTraceToString(),
                ErrorCategory.Internal
            )
            throw IllegalStateException("P3-8: 加密失败,拒绝持久化明文密码", e)
        }
        context.configDataStore.edit { it[KEY_CONFIG_JSON] = encrypted }
    }

    /**
     * 加密 JSON 中的 device.password 字段(方案 B:只加密 password,其余明文)。
     * 输入:明文 JSON,输出:device.password 替换为 "enc:<base64-iv+ciphertext>"。
     */
    private fun encryptPasswordInJson(plainJson: String): String {
        val jsonObj = json.parseToJsonElement(plainJson).jsonObject
        val deviceObj = jsonObj["device"]?.jsonObject ?: return plainJson
        val password = deviceObj["password"]?.jsonPrimitive?.content ?: return plainJson

        val encryptedPassword = encryptString(password)
        val newDeviceObj = JsonObject(deviceObj.toMutableMap().apply {
            put("password", json.parseToJsonElement("\"$ENC_PREFIX$encryptedPassword\""))
        })
        val newJsonObj = JsonObject(jsonObj.toMutableMap().apply {
            put("device", newDeviceObj)
        })
        return json.encodeToString(JsonObject.serializer(), newJsonObj)
    }

    /**
     * 解密 JSON 中的 device.password 字段。
     * 输入:device.password 可能是 "enc:<base64>" 或明文,输出:device.password 还原明文。
     */
    private fun decryptPasswordInJson(jsonString: String): String {
        val jsonObj = json.parseToJsonElement(jsonString).jsonObject
        val deviceObj = jsonObj["device"]?.jsonObject ?: return jsonString
        val password = deviceObj["password"]?.jsonPrimitive?.content ?: return jsonString

        if (!password.startsWith(ENC_PREFIX)) {
            // 老数据明文,直接返回
            return jsonString
        }

        val encryptedPayload = password.removePrefix(ENC_PREFIX)
        val decryptedPassword = decryptString(encryptedPayload)
        val newDeviceObj = JsonObject(deviceObj.toMutableMap().apply {
            put("password", json.parseToJsonElement("\"$decryptedPassword\""))
        })
        val newJsonObj = JsonObject(jsonObj.toMutableMap().apply {
            put("device", newDeviceObj)
        })
        return json.encodeToString(JsonObject.serializer(), newJsonObj)
    }

    /**
     * 加密字符串:AES-GCM(Android Keystore-backed)。
     * 输出:<base64(iv+ciphertext)>。
     */
    private fun encryptString(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密字符串:从 base64(iv+ciphertext) 还原明文。
     */
    private fun decryptString(encrypted: String): String {
        val combined = Base64.decode(encrypted, Base64.NO_WRAP)
        val iv = combined.sliceArray(0 until IV_LENGTH)
        val ciphertext = combined.sliceArray(IV_LENGTH until combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    companion object {
        private val KEY_CONFIG_JSON = stringPreferencesKey("sim_config_json")
        const val KEYSTORE_ALIAS = "uvp-sim-config-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BIT = 128
        private const val ENC_PREFIX = "enc:"

        /**
         * 仅测试用 — 暴露内部 DataStore 实例,Robolectric 测试可以在不重新声明
         * preferencesDataStore(同 name 会触发 DataStore 单例约束抛错)的前提下
         * 直接读 / seed raw JSON。
         *
         * 注:public 而非 internal,因为 :androidApp 与 :shared 是独立 module,
         * internal 不跨 module。 prefix `forTest` 标明仅测试用。
         */
        fun dataStoreForTest(context: Context): DataStore<Preferences> = context.configDataStore

        /** 仅测试用 — 暴露 stringPreferencesKey 避免测试代码重复声明。 */
        val KEY_CONFIG_JSON_FOR_TEST = KEY_CONFIG_JSON
    }
}

/**
 * 加密 key 提供方策略。生产用 [AndroidKeystoreKeyProvider],测试可 inject in-memory 实现
 * 绕开 Robolectric AndroidKeyStore 不可用问题。
 */
interface KeyProvider {
    fun getOrCreateKey(): SecretKey
}

/**
 * 默认实现:从 Android Keystore 拿 / 建 AES key。Keystore 提供硬件支持的密钥保护
 * (Android 6+ TEE / StrongBox),即使 root 设备也无法直接读出 raw key bytes。
 */
class AndroidKeystoreKeyProvider(private val alias: String) : KeyProvider {

    override fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        // 已存在则直接复用
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        // 不存在 → 生成新 AES-256 key,绑定到 Keystore
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}

/**
 * 测试用 in-memory AES key provider。**仅测试用**,绕开 AndroidKeyStore,
 * 生产代码绝不应 inject 这个实现 — key 在 JVM heap 上,root / 内存 dump 可读。
 */
class InMemoryKeyProvider : KeyProvider {
    private val key: SecretKey by lazy {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES)
        keyGenerator.init(256)
        keyGenerator.generateKey()
    }

    override fun getOrCreateKey(): SecretKey = key
}

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "uvp_sim_config")

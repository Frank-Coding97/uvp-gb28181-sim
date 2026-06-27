package com.uvp.sim.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P3-8 ConfigStoreAndroid password 加密测试(Robolectric)。
 *
 * 测试用例:
 * 1. roundtrip: 写入 password → 读回明文一致
 * 2. ciphertext: 写入后 DataStore raw bytes 不含明文 password
 * 3. legacyUpgrade: 老数据(明文 password)读出可用 + 自动升级写回 enc
 * 4. keystoreFailure: Keystore 失败 → 抛错拒绝持久化(skip — Robolectric mock Keystore 不易失效)
 *
 * 测试通过 ConfigStoreAndroid.dataStoreForTest()(internal helper)拿到 production
 * DataStore 实例。直接 declare 同 name 的 by preferencesDataStore 会触发 DataStore
 * "Currently active 1 datastore(s) for the same file" 单例约束抛错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigStoreAndroidEncryptionTest {

    private lateinit var context: Context
    private lateinit var store: ConfigStoreAndroid

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDataStoreFile()
        // Robolectric 的 AndroidKeyStore 不提供 AES key 生成,inject in-memory 实现绕开
        store = ConfigStoreAndroid(context, InMemoryKeyProvider())
        // 注:测试不绑定 SystemLogger scope — emit 走 trySend 到 unbound channel
        // 是安全 no-op,简化测试基础设施。
    }

    @After
    fun tearDown() {
        deleteDataStoreFile()
    }

    /**
     * Case 1: roundtrip — 写 password "secret123" → 读回明文 "secret123"。
     */
    @Test
    fun `roundtrip password encryption and decryption`() = runTest {
        val config = buildTestConfig(password = "secret123")

        store.save(config)
        val loaded = store.loadOnce(fallback = buildTestConfig(password = "fallback"))

        assertEquals("password 应明文读回", "secret123", loaded.device.password)
    }

    /**
     * Case 2: ciphertext — 写入后 DataStore 中存储的 JSON 不含明文 "secret123",
     * 且 stored JSON 含 "enc:" 前缀。
     *
     * 注:不直接读 preferences_pb 磁盘文件 — Robolectric 下 DataStore 写盘是 async 的,
     * 测试时序难保证。通过 DataStore API 读到的就是落盘内容(同样的 single source of truth)。
     */
    @Test
    fun `DataStore does not contain plaintext password after save`() = runTest {
        val config = buildTestConfig(password = "secret123")

        store.save(config)

        // 通过 public helper 拿到 production DataStore 实例,读 stored JSON
        val dataStore = ConfigStoreAndroid.dataStoreForTest(context)
        val snapshot = dataStore.data.first()
        val rawJson = snapshot[ConfigStoreAndroid.KEY_CONFIG_JSON_FOR_TEST]
            ?: error("DataStore empty")

        assertFalse(
            "DataStore JSON 应不含明文 password",
            rawJson.contains("secret123")
        )
        assertTrue(
            "DataStore JSON 应包含 enc: 前缀密文",
            rawJson.contains("enc:")
        )
    }

    /**
     * Case 3: legacyUpgrade — 老数据(明文 password)读出仍可用 + 自动升级写回。
     */
    @Test
    fun `legacy plaintext password auto-upgrade on first load`() = runTest {
        // 通过 internal helper 直接写入明文 JSON(模拟老版本数据)
        val plainJson = """
            {
              "gbVersion": "V2022",
              "server": {"ip": "10.0.0.1", "port": 5060, "serverId": "34020000002000000001", "domain": "3402000000", "allowList": []},
              "device": {"deviceId": "34020000001320000001", "name": "UVP-Sim", "videoChannelId": "34020000001320000001", "alarmChannelId": "34020000001320000002", "username": "34020000001320000001", "password": "old-plaintext-password"}
            }
        """.trimIndent()

        val dataStore = ConfigStoreAndroid.dataStoreForTest(context)
        dataStore.edit { prefs ->
            prefs[ConfigStoreAndroid.KEY_CONFIG_JSON_FOR_TEST] = plainJson
        }

        // 首次 load 应触发自动升级
        val loaded = store.loadOnce(fallback = buildTestConfig(password = "fallback"))
        assertEquals("老数据应读出明文密码", "old-plaintext-password", loaded.device.password)

        // 自动升级后,DataStore 应不再含明文
        val snapshot = dataStore.data.first()
        val rawJson = snapshot[ConfigStoreAndroid.KEY_CONFIG_JSON_FOR_TEST]
            ?: error("DataStore empty after upgrade")

        assertFalse(
            "自动升级后 DataStore 应不含明文密码",
            rawJson.contains("old-plaintext-password")
        )
        assertTrue(
            "自动升级后 DataStore 应含 enc: 密文",
            rawJson.contains("enc:")
        )
    }

    /**
     * Case 4: keystoreFailure — Keystore 不可用时,save 应抛 IllegalStateException
     * 拒绝持久化,而不是 silent fallback 明文写盘。
     *
     * 用 throwing KeyProvider 模拟 Keystore 失效(真机场景:Keystore 被锁 / 用户清空
     * 凭证 / Android 系统 bug)。
     */
    @Test(expected = IllegalStateException::class)
    fun `keystore failure rejects persistence`() = runTest {
        val failingStore = ConfigStoreAndroid(
            context,
            keyProvider = object : KeyProvider {
                override fun getOrCreateKey(): javax.crypto.SecretKey =
                    throw java.security.KeyStoreException("simulated keystore failure")
            }
        )
        failingStore.save(buildTestConfig(password = "secret123"))
    }

    private fun buildTestConfig(password: String): SimConfig {
        return SimConfig(
            server = ServerConfig(
                ip = "10.0.0.1",
                port = 5060,
                serverId = "34020000002000000001",
                domain = "3402000000"
            ),
            device = DeviceConfig(
                deviceId = "34020000001320000001",
                name = "Test Device",
                videoChannelId = "34020000001320000001",
                alarmChannelId = "34020000001320000002",
                username = "34020000001320000001",
                password = password
            )
        )
    }

    private fun dataStoreFile(): File =
        File(context.filesDir, "datastore/uvp_sim_config.preferences_pb")

    private fun deleteDataStoreFile() {
        dataStoreFile().delete()
    }
}

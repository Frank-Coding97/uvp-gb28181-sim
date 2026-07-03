package com.uvp.sim.app

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ConfigStoreIosTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun save_strips_password_from_userdefaults_json() = runTest {
        val jsonStore = InMemoryJsonStore()
        val passwordStore = InMemoryPasswordStore()
        val store = ConfigStoreIos(jsonStore, passwordStore)

        store.save(buildTestConfig(deviceId = "34020000001320000001", password = "secret123"))

        assertEquals("secret123", passwordStore.read("34020000001320000001"))
        assertFalse(jsonStore.raw.orEmpty().contains("secret123"), jsonStore.raw)
        val persisted = json.decodeFromString<SimConfig>(jsonStore.raw ?: error("missing json"))
        assertEquals("", persisted.device.password)
    }

    @Test
    fun load_restores_password_from_keychain() = runTest {
        val jsonStore = InMemoryJsonStore()
        val passwordStore = InMemoryPasswordStore()
        val store = ConfigStoreIos(jsonStore, passwordStore)
        jsonStore.write(json.encodeToString(buildTestConfig(password = "")))
        passwordStore.save("34020000001320000001", "from-keychain")

        val loaded = store.loadOnce(buildTestConfig(password = "fallback"))

        assertEquals("from-keychain", loaded.device.password)
    }

    @Test
    fun load_migrates_legacy_json_password_to_keychain() = runTest {
        val jsonStore = InMemoryJsonStore()
        val passwordStore = InMemoryPasswordStore()
        val store = ConfigStoreIos(jsonStore, passwordStore)
        jsonStore.write(json.encodeToString(buildTestConfig(password = "legacy-secret")))

        val loaded = store.loadOnce(buildTestConfig(password = "fallback"))

        assertEquals("legacy-secret", loaded.device.password)
        assertEquals("legacy-secret", passwordStore.read("34020000001320000001"))
        assertFalse(jsonStore.raw.orEmpty().contains("legacy-secret"), jsonStore.raw)
        val persisted = json.decodeFromString<SimConfig>(jsonStore.raw ?: error("missing json"))
        assertEquals("", persisted.device.password)
    }

    @Test
    fun empty_device_id_uses_default_account() = runTest {
        val jsonStore = InMemoryJsonStore()
        val passwordStore = InMemoryPasswordStore()
        val store = ConfigStoreIos(jsonStore, passwordStore)

        store.save(buildTestConfig(deviceId = "", password = "default-secret"))

        assertEquals("default-secret", passwordStore.read(KeychainStore.DEFAULT_ACCOUNT))
        assertNull(passwordStore.read(""))
    }

    // 2026-07-03 真机验发现:Personal Team 签名下 Keychain 写会静默 return false,
    // 老实现无条件 sanitize JSON 导致密码彻底丢失。守住:Keychain 失败时 JSON 必须保留明文。
    @Test
    fun save_keeps_password_in_json_when_keychain_write_fails() = runTest {
        val jsonStore = InMemoryJsonStore()
        val passwordStore = FailingPasswordStore()
        val store = ConfigStoreIos(jsonStore, passwordStore)

        store.save(buildTestConfig(deviceId = "34020000001320000001", password = "keep-me"))

        val persisted = json.decodeFromString<SimConfig>(jsonStore.raw ?: error("missing json"))
        assertEquals("keep-me", persisted.device.password)
    }

    @Test
    fun load_keeps_legacy_password_in_json_when_migration_fails() = runTest {
        val jsonStore = InMemoryJsonStore()
        val passwordStore = FailingPasswordStore()
        val store = ConfigStoreIos(jsonStore, passwordStore)
        jsonStore.write(json.encodeToString(buildTestConfig(password = "legacy-secret")))

        val loaded = store.loadOnce(buildTestConfig(password = "fallback"))

        assertEquals("legacy-secret", loaded.device.password)
        val persisted = json.decodeFromString<SimConfig>(jsonStore.raw ?: error("missing json"))
        assertEquals("legacy-secret", persisted.device.password)
    }

    private fun buildTestConfig(
        deviceId: String = "34020000001320000001",
        password: String,
    ): SimConfig = SimConfig(
        server = ServerConfig(
            ip = "10.0.0.1",
            port = 5060,
            serverId = "34020000002000000001",
            domain = "3402000000",
        ),
        device = DeviceConfig(
            deviceId = deviceId,
            name = "Test Device",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001320000002",
            username = deviceId,
            password = password,
        ),
    )
}

private class InMemoryJsonStore : IosConfigJsonStore {
    var raw: String? = null

    override fun read(): String? = raw

    override fun write(value: String) {
        raw = value
    }
}

private class InMemoryPasswordStore : DevicePasswordStore {
    private val values = mutableMapOf<String, String>()

    override fun read(account: String): String? = values[account]

    override fun save(account: String, password: String): Boolean {
        values[account] = password
        return true
    }

    override fun delete(account: String): Boolean {
        values.remove(account)
        return true
    }
}

private class FailingPasswordStore : DevicePasswordStore {
    override fun read(account: String): String? = null
    override fun save(account: String, password: String): Boolean = false
    override fun delete(account: String): Boolean = false
}

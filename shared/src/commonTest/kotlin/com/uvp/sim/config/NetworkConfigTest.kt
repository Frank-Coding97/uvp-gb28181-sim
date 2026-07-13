package com.uvp.sim.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T1 测试:NetworkConfig 数据类 + 序列化 + SimConfig 集成。
 *
 * 覆盖 plan §"T1 测试用例" 5 条:
 *   1. AUTO 默认值
 *   2. 序列化往返
 *   3. SimConfig 默认含 network 字段
 *   4. SimConfig 反序列化老配置(无 network 字段)
 *   5. 三个枚举值都能序列化/反序列化
 */
class NetworkConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun baseSimConfig(network: NetworkConfig? = null): SimConfig {
        return SimConfig(
            server = ServerConfig(
                ip = "127.0.0.1",
                serverId = "34020000002000000001",
                domain = "3402000000"
            ),
            device = DeviceConfig(
                deviceId = "34020000001320000001",
                videoChannelId = "34020000001310000001",
                alarmChannelId = "34020000001340000001",
                username = "34020000001320000001",
                password = "test-password"
            ),
        ).let { if (network != null) it.copy(network = network) else it }
    }

    @Test
    fun `default preference is AUTO`() {
        assertEquals(NetworkPreference.AUTO, NetworkConfig().preference)
    }

    @Test
    fun `roundtrip serialization preserves preference`() {
        val original = NetworkConfig(NetworkPreference.WIFI)
        val text = json.encodeToString(NetworkConfig.serializer(), original)
        val decoded = json.decodeFromString(NetworkConfig.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `SimConfig default contains network field with AUTO`() {
        val cfg = baseSimConfig()
        assertEquals(NetworkPreference.AUTO, cfg.network.preference)
    }

    @Test
    fun `SimConfig can deserialize legacy payload without network field`() {
        val legacy = """
            {
              "server": {
                "ip": "127.0.0.1",
                "serverId": "34020000002000000001",
                "domain": "3402000000"
              },
              "device": {
                "deviceId": "34020000001320000001",
                "videoChannelId": "34020000001310000001",
                "alarmChannelId": "34020000001340000001",
                "username": "34020000001320000001",
                "password": "test-password"
              }
            }
        """.trimIndent()
        val cfg = json.decodeFromString(SimConfig.serializer(), legacy)
        assertEquals(NetworkPreference.AUTO, cfg.network.preference)
    }

    @Test
    fun `all NetworkPreference enum values roundtrip`() {
        for (pref in NetworkPreference.entries) {
            val original = NetworkConfig(pref)
            val text = json.encodeToString(NetworkConfig.serializer(), original)
            val decoded = json.decodeFromString(NetworkConfig.serializer(), text)
            assertEquals(pref, decoded.preference, "roundtrip failed for $pref")
        }
    }
}

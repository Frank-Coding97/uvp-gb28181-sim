package com.uvp.sim.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiResponseConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyConfigDefaultsToFiftyRecordsPerPacket() {
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

        val config = json.decodeFromString(SimConfig.serializer(), legacy)

        assertEquals(50, config.multiResponsePageSize)
    }

    @Test
    fun configuredPageSizeSurvivesSerializationRoundTrip() {
        val original = SimConfig(
            server = ServerConfig(
                ip = "127.0.0.1",
                serverId = "34020000002000000001",
                domain = "3402000000",
            ),
            device = DeviceConfig(
                deviceId = "34020000001320000001",
                videoChannelId = "34020000001310000001",
                alarmChannelId = "34020000001340000001",
                username = "34020000001320000001",
                password = "test-password",
            ),
            multiResponsePageSize = 1,
        )

        val decoded = json.decodeFromString(
            SimConfig.serializer(),
            json.encodeToString(SimConfig.serializer(), original),
        )

        assertEquals(1, decoded.multiResponsePageSize)
    }
}

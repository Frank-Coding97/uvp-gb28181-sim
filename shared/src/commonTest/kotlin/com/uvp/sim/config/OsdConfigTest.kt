package com.uvp.sim.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OsdConfigTest {

    @Test
    fun defaultsTimestampAndChannelNameEnabledWatermarkDisabled() {
        val cfg = OsdConfig()
        assertTrue(cfg.timestamp.enabled, "timestamp default ON")
        assertTrue(cfg.channelName.enabled, "channelName default ON")
        assertTrue(!cfg.watermark.enabled, "watermark default OFF")
    }

    @Test
    fun defaultTimestampPositionTopLeft() {
        assertEquals(OsdPosition.TOP_LEFT, OsdConfig().timestamp.position)
    }

    @Test
    fun defaultChannelNamePositionTopRight() {
        assertEquals(OsdPosition.TOP_RIGHT, OsdConfig().channelName.position)
    }

    @Test
    fun defaultWatermarkPositionBottomRight() {
        assertEquals(OsdPosition.BOTTOM_RIGHT, OsdConfig().watermark.position)
    }

    @Test
    fun serializationRoundTrip() {
        val original = OsdConfig(
            timestamp = OsdLayer(
                enabled = true,
                text = "",
                position = OsdPosition.TOP_LEFT,
                size = OsdSize.LARGE,
                fillColor = "#FF0000",
                outlineColor = "#000000"
            ),
            channelName = OsdLayer(
                enabled = true,
                text = "正门入口",
                position = OsdPosition.CENTER,
                size = OsdSize.SMALL,
                fillColor = "#00FF00",
                outlineColor = "#FFFFFF"
            ),
            watermark = OsdLayer(
                enabled = false,
                text = "@DEMO",
                position = OsdPosition.BOTTOM_RIGHT,
                size = OsdSize.MEDIUM,
                fillColor = "#FFFFFF",
                outlineColor = "#000000"
            )
        )
        val json = Json.encodeToString(OsdConfig.serializer(), original)
        val decoded = Json.decodeFromString(OsdConfig.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun simConfigContainsOsdNonNull() {
        val sim = SimConfig(
            server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
            device = DeviceConfig(
                deviceId = "34020000001110000001",
                videoChannelId = "34020000001320000001",
                alarmChannelId = "34020000001340000001",
                username = "admin",
                password = "test-password"
            )
        )
        assertNotNull(sim.osd)
        assertTrue(sim.osd.timestamp.enabled)
    }
}

package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T2 — AlarmPayload 默认值 + @Serializable round-trip + quickDefault。
 */
class AlarmPayloadTest {

    private val config = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001310000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "test-password"
        )
    )

    @Test
    fun `默认值正确`() {
        val p = AlarmPayload(deviceId = "dev1")
        assertEquals(AlarmPriority.General, p.priority)
        assertEquals(AlarmMethod.Device, p.method)
        assertEquals(AlarmType.Other, p.type)
        assertEquals(null, p.typeParam)
        assertEquals(0L, p.timeMs)
        assertEquals("", p.description)
        assertEquals(null, p.longitude)
        assertEquals(null, p.latitude)
    }

    @Test
    fun `round-trip 编解码同对象`() {
        val p = AlarmPayload(
            deviceId = "dev1",
            priority = AlarmPriority.EmergencyL2,
            method = AlarmMethod.Video,
            type = AlarmType.VideoLost,
            typeParam = "磁吸触发",
            timeMs = 1_700_000_000_000L,
            description = "大门通道画面丢失",
            longitude = 116.4,
            latitude = 39.9
        )
        val json = Json.encodeToString(AlarmPayload.serializer(), p)
        val back = Json.decodeFromString(AlarmPayload.serializer(), json)
        assertEquals(p, back)
    }

    @Test
    fun `quickDefault 用 alarmChannelId`() {
        val p = AlarmPayload.quickDefault(config)
        assertEquals(config.device.alarmChannelId, p.deviceId)
    }
}

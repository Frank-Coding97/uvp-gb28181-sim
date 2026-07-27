package com.uvp.sim.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelIdGeneratorTest {

    @Test
    fun `deriveVideoChannelId format and suffix`() {
        val deviceId = "34020000001320000001"
        val result = ChannelIdGenerator.deriveVideoChannelId(deviceId)

        assertEquals(20, result.length, "videoChannelId must be 20 characters")
        assertTrue(result.startsWith("3402000000132"), "First 13 chars must match deviceId")
        assertTrue(result.endsWith("0000010"), "Last 7 chars must be 0000010")
        assertEquals("34020000001320000010", result)
    }

    @Test
    fun `deriveFrontChannelId format and suffix`() {
        val deviceId = "34020000001320000001"
        val result = ChannelIdGenerator.deriveFrontChannelId(deviceId)

        assertEquals(20, result.length)
        assertTrue(result.startsWith("3402000000132"))
        assertTrue(result.endsWith("0000020"))
        assertEquals("34020000001320000020", result)
    }

    @Test
    fun `deriveAlarmChannelId format and suffix`() {
        val deviceId = "34020000001320000001"
        val result = ChannelIdGenerator.deriveAlarmChannelId(deviceId)

        assertEquals(20, result.length)
        assertTrue(result.startsWith("3402000000132"))
        assertTrue(result.endsWith("0000001"))
        assertEquals("34020000001320000001", result)
    }

    @Test
    fun `different deviceIds produce different channel prefixes`() {
        val device1 = "30000000000010000001"
        val device2 = "31111111111110000001"

        val video1 = ChannelIdGenerator.deriveVideoChannelId(device1)
        val video2 = ChannelIdGenerator.deriveVideoChannelId(device2)

        assertTrue(video1 != video2, "Different deviceIds should produce different channelIds")
        assertTrue(video1.startsWith(device1.take(13)))
        assertTrue(video2.startsWith(device2.take(13)))
    }

    @Test
    fun `invalid deviceId length throws`() {
        val shortId = "123456"
        val exception = runCatching { ChannelIdGenerator.deriveVideoChannelId(shortId) }
        assertTrue(exception.isFailure, "Short deviceId should throw")
    }
}

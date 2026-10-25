package com.uvp.sim.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceIdGeneratorTest {

    @Test
    fun `deriveDeviceId is deterministic`() {
        val hw = "test-hardware-id-12345"
        val result1 = DeviceIdGenerator.deriveDeviceId(hw)
        val result2 = DeviceIdGenerator.deriveDeviceId(hw)
        assertEquals(result1, result2, "Same input must produce same output")
    }

    @Test
    fun `deriveDeviceId format is 20 digits with fixed prefix`() {
        val hw = "abcd1234-efgh-5678-ijkl-9012mnop3456"
        val result = DeviceIdGenerator.deriveDeviceId(hw)

        assertEquals(20, result.length, "deviceId must be 20 characters")
        assertTrue(result.all { it.isDigit() }, "deviceId must contain only digits")
        assertEquals("34020000", result.substring(0, 8), "中心编码")
        assertEquals("00", result.substring(8, 10), "行业编码")
    }

    @Test
    fun `device id type segment is always 132`() {
        val samples = (1..50).map { "hw-$it" }
        samples.forEach { hw ->
            val id = DeviceIdGenerator.deriveDeviceId(hw)
            assertEquals("132", id.substring(10, 13), "类型段必须是 132（IPC），实际 $hw -> $id")
        }
    }

    @Test
    fun `device id network flag is always 7`() {
        val samples = (1..50).map { "nf-$it" }
        samples.forEach { hw ->
            assertEquals('7', DeviceIdGenerator.deriveDeviceId(hw)[13], "第 14 位网络标识必须是 7")
        }
    }

    @Test
    fun `device id never collides with its own channels`() {
        // 设备 第 14 位 = 7，通道 第 14 位 = 1(后置/报警) 或 2(前置)，两者永远不同号。
        val samples = (1..200).map { "collision-$it" }
        samples.forEach { hw ->
            val deviceId = DeviceIdGenerator.deriveDeviceId(hw)
            val channels = listOf(
                ChannelIdGenerator.deriveVideoChannelId(deviceId),
                ChannelIdGenerator.deriveFrontChannelId(deviceId),
                ChannelIdGenerator.deriveAlarmChannelId(deviceId),
            )
            assertFalse(deviceId in channels, "设备编码不能和自身通道编码相同：$deviceId")
            assertEquals(3, channels.toSet().size, "三个通道编码互不相同：$channels")
        }
    }

    @Test
    fun `different inputs produce different outputs`() {
        val hw1 = "android-id-abc123"
        val hw2 = "ios-vendor-uuid-xyz789"

        val result1 = DeviceIdGenerator.deriveDeviceId(hw1)
        val result2 = DeviceIdGenerator.deriveDeviceId(hw2)

        assertTrue(result1 != result2, "Different inputs should produce different deviceIds")
    }

    @Test
    fun `distribution check - 100 fixed inputs`() {
        // 用固定输入而不是随机输入：序号空间已缩到 100 万，随机输入有约 0.5% 的撞号概率，
        // 会变成偶发红灯。固定输入的结果恒定，用例可复现。
        val inputs = (1..100).map { "hardware-id-$it" }
        val outputs = inputs.map { DeviceIdGenerator.deriveDeviceId(it) }.toSet()

        assertEquals(100, outputs.size, "100 个固定输入应当产出 100 个互不相同的 deviceId")
    }

    @Test
    fun `empty input throws`() {
        val exception = runCatching { DeviceIdGenerator.deriveDeviceId("") }
        assertTrue(exception.isFailure, "Empty hardwareId should throw")
    }
}

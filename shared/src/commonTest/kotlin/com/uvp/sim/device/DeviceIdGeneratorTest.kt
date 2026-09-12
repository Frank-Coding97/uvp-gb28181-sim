package com.uvp.sim.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

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
        assertTrue(result.startsWith("34020000001"), "deviceId must start with 34020000001")
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
    fun `distribution check - 100 random inputs`() {
        val inputs = (1..100).map { "hardware-id-$it-${Clock.System.now().toEpochMilliseconds()}" }
        val outputs = inputs.map { DeviceIdGenerator.deriveDeviceId(it) }.toSet()

        // 100 个输入应该产生 100 个不同输出（概率上几乎必然，冲突概率 < 100^2 / 10^19）
        assertEquals(100, outputs.size, "100 random inputs should produce 100 unique deviceIds")
    }

    @Test
    fun `empty input throws`() {
        val exception = runCatching { DeviceIdGenerator.deriveDeviceId("") }
        assertTrue(exception.isFailure, "Empty hardwareId should throw")
    }
}

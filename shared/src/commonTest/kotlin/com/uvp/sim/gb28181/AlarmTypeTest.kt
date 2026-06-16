package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T1 — GB/T 28181-2022 §A.2.5 / §9.5.1 报警枚举 code/label + byCode 反查。
 */
class AlarmTypeTest {

    @Test
    fun `AlarmPriority 4 个 code 唯一 1-4`() {
        val codes = AlarmPriority.entries.map { it.code }
        assertEquals(listOf(1, 2, 3, 4), codes.sorted())
        assertEquals(4, codes.toSet().size)
    }

    @Test
    fun `AlarmMethod 7 个 code 唯一 1-7`() {
        val codes = AlarmMethod.entries.map { it.code }
        assertEquals((1..7).toList(), codes.sorted())
        assertEquals(7, codes.toSet().size)
    }

    @Test
    fun `AlarmType 5 个 code 唯一 1-5`() {
        val codes = AlarmType.entries.map { it.code }
        assertEquals((1..5).toList(), codes.sorted())
        assertEquals(5, codes.toSet().size)
    }

    @Test
    fun `byCode 反查正确`() {
        assertEquals(AlarmPriority.EmergencyL1, AlarmPriority.byCode(1))
        assertEquals(AlarmPriority.General, AlarmPriority.byCode(4))
        assertEquals(AlarmMethod.Video, AlarmMethod.byCode(5))
        assertEquals(AlarmType.VideoLost, AlarmType.byCode(1))
        assertEquals(AlarmType.Other, AlarmType.byCode(5))
    }

    @Test
    fun `byCode missing 返回 null`() {
        assertNull(AlarmPriority.byCode(0))
        assertNull(AlarmPriority.byCode(99))
        assertNull(AlarmMethod.byCode(8))
        assertNull(AlarmType.byCode(6))
    }
}

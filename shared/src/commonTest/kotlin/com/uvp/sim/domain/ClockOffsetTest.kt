package com.uvp.sim.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockOffsetTest {

    @Test
    fun `Empty 未校时降级本地墙钟`() {
        val o = ClockOffset.Empty
        assertFalse(o.isSynced)
        val adjusted = o.adjustedNowMs()
        val local = Clock.System.now().toEpochMilliseconds()
        assertTrue(abs(adjusted - local) < 200, "adjusted=$adjusted local=$local")
    }

    @Test
    fun `Empty localOffsetMs 返回 null`() {
        assertNull(ClockOffset.Empty.localOffsetMs())
    }

    @Test
    fun `synced 后立即调 adjustedNowMs 等于平台基准`() {
        val baseInstant = Instant.parse("2026-06-18T07:30:00Z")
        val o = ClockOffset.synced(baseInstant, "Wed, 18 Jun 2026 07:30:00 GMT")
        assertTrue(o.isSynced)
        val adjusted = o.adjustedNowMs()
        val expected = baseInstant.toEpochMilliseconds()
        // 允许 ±200ms(synced 内部读单调时钟到这里调用之间的微小流逝)
        assertTrue(abs(adjusted - expected) < 200, "adjusted=$adjusted expected=$expected")
    }

    @Test
    fun `synced 后流逝时间反映在 adjustedNowMs 上`() = runBlocking {
        val baseInstant = Instant.parse("2026-06-18T07:30:00Z")
        val o = ClockOffset.synced(baseInstant, "iso")
        val before = o.adjustedNowMs()
        delay(80)
        val after = o.adjustedNowMs()
        val delta = after - before
        // 流逝大约 80ms,放宽 [60, 250]
        assertTrue(delta in 60..250, "delta=$delta(预期 ~80ms)")
    }

    @Test
    fun `synced 保留 rawDateHeader`() {
        val raw = "Wed, 18 Jun 2026 07:30:00 GMT"
        val o = ClockOffset.synced(Instant.parse("2026-06-18T07:30:00Z"), raw)
        assertEquals(raw, o.rawDateHeader)
    }

    @Test
    fun `synced localOffsetMs 计算合法`() {
        // baseInstant 用动态当前时刻(不再硬编码日期),
        // synced() 内部 recvLocalMs = Clock.System.now().toEpochMilliseconds() 几乎同时取,
        // offset 应接近 0,严格断言 < 1 分钟既稳定又有意义
        val baseInstant = Clock.System.now()
        val o = ClockOffset.synced(baseInstant, "raw")
        val offset = o.localOffsetMs()
        assertNotNull(offset)
        assertTrue(abs(offset) < 60_000, "offset=$offset 应接近 0(平台基准 ≈ 本地时刻)")
    }
}

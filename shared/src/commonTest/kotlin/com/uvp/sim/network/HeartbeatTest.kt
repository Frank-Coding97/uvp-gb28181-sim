package com.uvp.sim.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatTest {

    @Test fun startTriggersTickAfterInterval() = runTest {
        var ticks = 0
        val hb = Heartbeat(intervalMillis = 1000, scope = this) { ticks++ }
        hb.start()
        // 立即启动后 0 tick
        assertEquals(0, ticks)
        // 移动 1.5s 后,1 tick
        advanceTimeBy(1500)
        assertEquals(1, ticks)
        // 再移动 2s 后,3 tick
        advanceTimeBy(2000)
        assertEquals(3, ticks)
        hb.stop()
    }

    @Test fun stopHaltsTicks() = runTest {
        var ticks = 0
        val hb = Heartbeat(intervalMillis = 100, scope = this) { ticks++ }
        hb.start()
        advanceTimeBy(350)
        assertEquals(3, ticks)
        hb.stop()
        advanceTimeBy(1000)
        assertEquals(3, ticks)
    }

    @Test fun startWhileRunningIsNoop() = runTest {
        var ticks = 0
        val hb = Heartbeat(intervalMillis = 100, scope = this) { ticks++ }
        hb.start()
        hb.start()  // second call should not double
        hb.start()
        advanceTimeBy(350)
        assertEquals(3, ticks)
        hb.stop()
    }

    @Test fun isRunningReflectsState() = runTest {
        val hb = Heartbeat(100, this) {}
        assertFalse(hb.isRunning)
        hb.start()
        assertTrue(hb.isRunning)
        hb.stop()
        assertFalse(hb.isRunning)
    }
}

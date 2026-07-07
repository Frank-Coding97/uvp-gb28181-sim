package com.uvp.sim.media

import com.uvp.sim.camera.IosAudioStreamer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-E2-2:iOS BroadcastBusyGate actual 分支测试(读 IosAudioStreamer.activeCount)。
 */
class BroadcastBusyGateIosTest {

    @BeforeTest
    fun reset() {
        IosAudioStreamer.resetActiveCountForTest()
    }

    @AfterTest
    fun cleanup() {
        IosAudioStreamer.resetActiveCountForTest()
    }

    @Test
    fun idle_when_active_count_zero() {
        assertEquals(0, IosAudioStreamer.activeCount)
        assertFalse(BroadcastBusyGate.isBusy())
        assertNull(BroadcastBusyGate.busyReason())
    }

    @Test
    fun busy_when_active_count_gt_zero() {
        // 模拟录像启动导致 activeCount++
        IosAudioStreamer.activeCountAtomic.value = 1
        assertTrue(BroadcastBusyGate.isBusy())
        val reason = BroadcastBusyGate.busyReason()
        assertNotNull(reason)
        assertEquals("recording-active", reason)
    }

    @Test
    fun still_busy_when_multiple_active_streams() {
        IosAudioStreamer.activeCountAtomic.value = 3
        assertTrue(BroadcastBusyGate.isBusy())
    }
}

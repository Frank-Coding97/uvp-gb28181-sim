package com.uvp.sim.media

import com.uvp.sim.camera.IosAudioStreamer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * iOS 上行采集与广播播放共用 AVAudioSession,不能因 audio tap 活跃拒绝广播。
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
    fun active_uplink_does_not_block_broadcast() {
        IosAudioStreamer.activeCountAtomic.value = 1
        assertFalse(BroadcastBusyGate.isBusy())
        assertNull(BroadcastBusyGate.busyReason())
    }

    @Test
    fun multiple_uplink_streams_do_not_block_broadcast() {
        IosAudioStreamer.activeCountAtomic.value = 3
        assertFalse(BroadcastBusyGate.isBusy())
        assertNull(BroadcastBusyGate.busyReason())
    }
}

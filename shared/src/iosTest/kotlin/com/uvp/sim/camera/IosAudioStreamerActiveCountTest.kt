package com.uvp.sim.camera

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-E2-4:验 IosAudioStreamer.activeCount 计数正确性(嵌套 start/stop 3 次不泄漏)。
 *
 * 由于 CoreAudio 在 iosSimulator 上启动 tap 可能受权限限制(mic 需要授权),
 * 本测试**直接操作 companion object 的 activeCountAtomic**,验计数语义 + clamp。
 * 真正的 start/stop 语义链在 T-E3-3 真机联调验。
 */
class IosAudioStreamerActiveCountTest {

    @BeforeTest
    fun reset() {
        IosAudioStreamer.resetActiveCountForTest()
    }

    @AfterTest
    fun cleanup() {
        IosAudioStreamer.resetActiveCountForTest()
    }

    @Test
    fun initial_count_is_zero() {
        assertEquals(0, IosAudioStreamer.activeCount)
    }

    @Test
    fun increment_three_times_yields_three() {
        IosAudioStreamer.activeCountAtomic.incrementAndGet()
        IosAudioStreamer.activeCountAtomic.incrementAndGet()
        IosAudioStreamer.activeCountAtomic.incrementAndGet()
        assertEquals(3, IosAudioStreamer.activeCount)
    }

    @Test
    fun paired_decrement_zeros_out() {
        IosAudioStreamer.activeCountAtomic.incrementAndGet()
        IosAudioStreamer.activeCountAtomic.incrementAndGet()
        IosAudioStreamer.activeCountAtomic.incrementAndGet()
        IosAudioStreamer.decrementActiveCountClamped()
        IosAudioStreamer.decrementActiveCountClamped()
        IosAudioStreamer.decrementActiveCountClamped()
        assertEquals(0, IosAudioStreamer.activeCount)
    }

    @Test
    fun extra_decrement_is_clamped_to_zero() {
        IosAudioStreamer.decrementActiveCountClamped()
        IosAudioStreamer.decrementActiveCountClamped()
        assertEquals(0, IosAudioStreamer.activeCount, "clamp 不减到负")
    }

    @Test
    fun mixed_start_stop_stays_balanced() {
        IosAudioStreamer.activeCountAtomic.incrementAndGet()
        assertEquals(1, IosAudioStreamer.activeCount)
        IosAudioStreamer.decrementActiveCountClamped()
        assertEquals(0, IosAudioStreamer.activeCount)
        IosAudioStreamer.activeCountAtomic.incrementAndGet()
        IosAudioStreamer.activeCountAtomic.incrementAndGet()
        assertEquals(2, IosAudioStreamer.activeCount)
        IosAudioStreamer.decrementActiveCountClamped()
        assertEquals(1, IosAudioStreamer.activeCount)
    }
}

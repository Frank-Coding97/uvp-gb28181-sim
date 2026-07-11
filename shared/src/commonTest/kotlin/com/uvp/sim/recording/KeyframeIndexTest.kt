package com.uvp.sim.recording

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyframeIndexTest {

    @Test
    fun sorts_points_and_returns_last_sync_at_or_before_target() {
        val index = KeyframeIndex()
        index.add(2_000L)
        index.add(0L)
        index.add(1_000L)
        index.finalizeIndex()

        assertEquals(1_000L, index.findPreviousSync(1_500L))
        assertEquals(2_000L, index.findPreviousSync(3_000L))
    }

    @Test
    fun target_before_first_sync_returns_first_sync() {
        val index = KeyframeIndex()
        index.add(1_000L)
        index.add(2_000L)
        index.finalizeIndex()

        assertEquals(1_000L, index.findPreviousSync(100L))
    }

    @Test
    fun empty_index_uses_fallback() {
        val index = KeyframeIndex()
        index.finalizeIndex()

        assertEquals(42L, index.findPreviousSync(1_000L, fallbackUs = 42L))
    }
}

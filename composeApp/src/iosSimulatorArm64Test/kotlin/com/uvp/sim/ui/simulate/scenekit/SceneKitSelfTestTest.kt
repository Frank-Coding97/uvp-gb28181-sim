package com.uvp.sim.ui.simulate.scenekit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v1.3-C · T-C3-6 SceneKitSelfTest 时段验证.
 */
class SceneKitSelfTestTest {

    @Test
    fun sample_at_zero_returns_zero() {
        val s = SceneKitSelfTest.sample(0.0)
        assertEquals(0f, s.pan, 0.001f)
        assertEquals(0f, s.tilt, 0.001f)
        assertFalse(s.done)
    }

    @Test
    fun sample_pan_reaches_negative_amplitude_at_1_5s() {
        val s = SceneKitSelfTest.sample(1.5)
        // 1.5s 是 pan 0 → -50 段的末尾, 应接近 -50
        assertTrue(abs(s.pan - (-50f)) < 1.5f, "pan @1.5s should be ~-50, actual ${s.pan}")
        assertEquals(0f, s.tilt, 0.001f)
    }

    @Test
    fun sample_pan_reaches_positive_amplitude_at_2_5s() {
        val s = SceneKitSelfTest.sample(2.5)
        // 2.5s 是 pan -50 → +50 段末尾
        assertTrue(abs(s.pan - 50f) < 1.5f, "pan @2.5s should be ~50, actual ${s.pan}")
    }

    @Test
    fun sample_pan_returns_to_zero_at_3s() {
        val s = SceneKitSelfTest.sample(3.0)
        assertTrue(abs(s.pan) < 1.5f, "pan @3s should be ~0, actual ${s.pan}")
    }

    @Test
    fun sample_tilt_reaches_positive_at_4_5s() {
        val s = SceneKitSelfTest.sample(4.5)
        assertTrue(abs(s.tilt - 25f) < 1.5f, "tilt @4.5s should be ~25, actual ${s.tilt}")
        assertEquals(0f, s.pan, 0.001f)
    }

    @Test
    fun sample_done_true_after_6s() {
        val s = SceneKitSelfTest.sample(6.5)
        assertTrue(s.done)
        assertEquals(0f, s.pan, 0.001f)
        assertEquals(0f, s.tilt, 0.001f)
    }

    @Test
    fun sample_done_false_before_6s() {
        val s = SceneKitSelfTest.sample(5.9)
        assertFalse(s.done)
    }
}

package com.uvp.sim.snapshot

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * commonTest 跑所有 target 的 actual stub:JVM 端必须返 null,iOS stub 同行为。
 * Android actual 在真机覆盖,不进 commonTest。
 */
class SnapshotCaptureStubTest {

    // T3.1
    @Test
    fun stub_returns_null() = runTest {
        val capture = SnapshotCapture()
        assertNull(capture.takeJpeg())
    }

    // T3.2
    @Test
    fun stub_does_not_throw_on_repeated_calls() = runTest {
        val capture = SnapshotCapture()
        repeat(5) { assertNull(capture.takeJpeg()) }
    }
}

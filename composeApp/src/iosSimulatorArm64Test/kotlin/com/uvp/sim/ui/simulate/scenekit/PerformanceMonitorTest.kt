package com.uvp.sim.ui.simulate.scenekit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v1.3-C · T-C4-1 PerformanceMonitor 单测.
 */
class PerformanceMonitorTest {

    @Test
    fun averageFps_returns_60_when_16_67ms_intervals() {
        val monitor = PerformanceMonitor(windowSec = 60)
        // 3600 帧, 每 16.667ms 一次 (60fps)
        val intervalNs = 16_666_666L
        repeat(3600) { monitor.recordFrame(it * intervalNs) }
        val fps = monitor.averageFps
        assertTrue(abs(fps - 60f) < 1f, "expected ~60fps, actual $fps")
    }

    @Test
    fun averageFps_returns_30_when_33_33ms_intervals() {
        val monitor = PerformanceMonitor(windowSec = 60)
        val intervalNs = 33_333_333L
        repeat(1800) { monitor.recordFrame(it * intervalNs) }
        val fps = monitor.averageFps
        assertTrue(abs(fps - 30f) < 1f, "expected ~30fps, actual $fps")
    }

    @Test
    fun averageFps_zero_with_less_than_two_frames() {
        val monitor = PerformanceMonitor(windowSec = 60)
        assertEquals(0f, monitor.averageFps)
        monitor.recordFrame(0)
        assertEquals(0f, monitor.averageFps, "single frame → 0 fps")
    }

    @Test
    fun window_slides_out_old_frames() {
        val monitor = PerformanceMonitor(windowSec = 1)  // 1s 窗口
        val intervalNs = 16_666_666L
        // 先塞 100 帧, 时间跨度约 1.65s(会有帧被 slid out)
        repeat(100) { monitor.recordFrame(it * intervalNs) }
        // 只剩 ~60 帧 (最后 1s)
        assertTrue(monitor.frameCount in 55..65, "frameCount after slide: ${monitor.frameCount}")
    }

    @Test
    fun reset_clears_all_frames() {
        val monitor = PerformanceMonitor()
        repeat(10) { monitor.recordFrame(it * 16_666_666L) }
        monitor.reset()
        assertEquals(0, monitor.frameCount)
    }
}

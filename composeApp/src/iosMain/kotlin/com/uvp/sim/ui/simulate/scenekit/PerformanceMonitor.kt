package com.uvp.sim.ui.simulate.scenekit

/**
 * v1.3-C · T-C4-1 PerformanceMonitor — 60s 滚动窗口 fps 计算.
 *
 * 用法(伪代码):
 * ```
 * val monitor = PerformanceMonitor(windowSec = 60)
 * scnView.rendererDelegate = ... {
 *     override fun renderer(_:willRenderScene:atTime:) {
 *         monitor.recordFrame(now)
 *         val avg = monitor.averageFps
 *     }
 * }
 * ```
 *
 * 纯逻辑,不依赖 SceneKit(便于单测). 精度:
 * - 时间戳单位纳秒(SceneKit 侧 CACurrentMediaTime() * 1e9)
 * - 窗口内 <2 帧时返回 0f
 */
class PerformanceMonitor(
    /** 滚动窗口大小(秒),默认 60s. */
    val windowSec: Int = 60,
) {
    private val windowNanos: Long = windowSec.toLong() * 1_000_000_000L
    private val timestamps: ArrayDeque<Long> = ArrayDeque()

    /**
     * 记录一帧时间戳(纳秒). 超出窗口的老帧自动出队.
     */
    fun recordFrame(nanoTime: Long) {
        timestamps.addLast(nanoTime)
        val cutoff = nanoTime - windowNanos
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
        }
    }

    /**
     * 当前窗口内平均 fps. 窗口内 <2 帧时返回 0f.
     */
    val averageFps: Float
        get() {
            val n = timestamps.size
            if (n < 2) return 0f
            val first = timestamps.first()
            val last = timestamps.last()
            val spanSec = (last - first).toDouble() / 1_000_000_000.0
            if (spanSec <= 0.0) return 0f
            return ((n - 1) / spanSec).toFloat()
        }

    /** 窗口内帧数. */
    val frameCount: Int get() = timestamps.size

    /** 清空(比如切换 scene 时). */
    fun reset() {
        timestamps.clear()
    }
}

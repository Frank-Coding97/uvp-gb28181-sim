package com.uvp.sim.sip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M-2 (audit §3) — SIP dialog 空闲超时 + GC 单元测试。
 *
 * 用 runTest 虚拟时间避免真 delay。验证:
 *  - touch 后 idleMs 内不被 GC
 *  - 超过 idleMs 后 gcOnce 清理 + 触发 onStale
 *  - start() 周期协程自动清理
 *  - forget 移除后不再触发 stale
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DialogTimeoutTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun freshDialogNotGced() = runTest(dispatcher) {
        var virtualNow = 0L
        val stale = mutableListOf<String>()
        val tracker = SipDialogIdleTracker(
            scope = this,
            idleMs = 5_000L,
            gcIntervalMs = 1_000L,
            nowMs = { virtualNow },
            onStale = { id, _ -> stale += id },
        )
        tracker.touch("dialog-1")
        virtualNow = 4_999L
        val cleared = tracker.gcOnce()
        assertTrue(cleared.isEmpty(), "idleMs 未到不应该 GC")
        assertTrue(stale.isEmpty())
        assertEquals(1, tracker.activeCount())
    }

    @Test
    fun idleDialogGcedWithOnStaleFired() = runTest(dispatcher) {
        var virtualNow = 0L
        val stale = mutableListOf<String>()
        val tracker = SipDialogIdleTracker(
            scope = this,
            idleMs = 5_000L,
            gcIntervalMs = 1_000L,
            nowMs = { virtualNow },
            onStale = { id, _ -> stale += id },
        )
        tracker.touch("dialog-a")
        tracker.touch("dialog-b")
        virtualNow = 5_000L
        val cleared = tracker.gcOnce()
        assertEquals(setOf("dialog-a", "dialog-b"), cleared.toSet())
        assertEquals(setOf("dialog-a", "dialog-b"), stale.toSet())
        assertEquals(0, tracker.activeCount())
    }

    @Test
    fun touchRefreshesLastActivity() = runTest(dispatcher) {
        var virtualNow = 0L
        val tracker = SipDialogIdleTracker(
            scope = this,
            idleMs = 5_000L,
            gcIntervalMs = 1_000L,
            nowMs = { virtualNow },
            onStale = { _, _ -> },
        )
        tracker.touch("dialog-x")
        virtualNow = 4_000L
        tracker.touch("dialog-x")  // 再次活动
        virtualNow = 7_000L  // 距上次 touch 仅 3s,未超
        assertTrue(tracker.gcOnce().isEmpty())
        virtualNow = 9_001L  // 距上次 touch 5001s,超
        assertEquals(listOf("dialog-x"), tracker.gcOnce())
    }

    @Test
    fun forgetRemovesDialogWithoutStaleFire() = runTest(dispatcher) {
        var virtualNow = 0L
        val stale = mutableListOf<String>()
        val tracker = SipDialogIdleTracker(
            scope = this,
            idleMs = 5_000L,
            gcIntervalMs = 1_000L,
            nowMs = { virtualNow },
            onStale = { id, _ -> stale += id },
        )
        tracker.touch("dialog-bye")
        tracker.forget("dialog-bye")
        virtualNow = 10_000L
        assertTrue(tracker.gcOnce().isEmpty())
        assertTrue(stale.isEmpty(), "forget 后即使过期也不应触发 onStale")
    }

    @Test
    fun startKicksPeriodicGc() = runTest(dispatcher) {
        var virtualNow = 0L
        val stale = mutableListOf<String>()
        val tracker = SipDialogIdleTracker(
            scope = this,
            idleMs = 5_000L,
            gcIntervalMs = 1_000L,
            nowMs = { virtualNow },
            onStale = { id, _ -> stale += id },
        )
        tracker.touch("auto-gc-dialog")
        tracker.start()
        // 推进 5.5s,nowMs 同步,期间应该至少跑过 5 次 GC,最后一次触发清理
        for (step in 1..6) {
            virtualNow += 1_000L
            advanceTimeBy(1_001L)
            runCurrent()
        }
        assertEquals(listOf("auto-gc-dialog"), stale, "周期 GC 自动捕获 stale dialog")
        tracker.stop()
    }
}

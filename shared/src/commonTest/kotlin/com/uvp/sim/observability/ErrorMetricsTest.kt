package com.uvp.sim.observability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3-3 (Wave 5 PR-OBSERVABILITY) — 验证 [ErrorMetrics] 的 increment / snapshot / reset
 * 在并发场景下计数正确(Mutex 串行)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorMetricsTest {

    @BeforeTest
    fun setup() = runTest { ErrorMetrics.reset() }

    @AfterTest
    fun teardown() = runTest { ErrorMetrics.reset() }

    @Test
    fun increment_then_snapshot_reflects_counter() = runTest {
        ErrorMetrics.increment("send INVITE", ErrorCategory.Transient)
        ErrorMetrics.increment("send INVITE", ErrorCategory.Transient)
        ErrorMetrics.increment("RTP bind", ErrorCategory.Internal)

        val snap = ErrorMetrics.snapshot()
        assertEquals(2L, snap["send INVITE" to ErrorCategory.Transient])
        assertEquals(1L, snap["RTP bind" to ErrorCategory.Internal])
        assertEquals(2, snap.size)
    }

    @Test
    fun snapshot_is_immutable_copy() = runTest {
        ErrorMetrics.increment("ctx", ErrorCategory.UserInput)
        val snap1 = ErrorMetrics.snapshot()
        ErrorMetrics.increment("ctx", ErrorCategory.UserInput)
        val snap2 = ErrorMetrics.snapshot()
        // snap1 不应被后续 increment 影响
        assertEquals(1L, snap1["ctx" to ErrorCategory.UserInput])
        assertEquals(2L, snap2["ctx" to ErrorCategory.UserInput])
    }

    @Test
    fun reset_clears_all_counters() = runTest {
        ErrorMetrics.increment("a", ErrorCategory.Transient)
        ErrorMetrics.increment("b", ErrorCategory.Permanent)
        ErrorMetrics.reset()
        assertTrue(ErrorMetrics.snapshot().isEmpty(), "expected snapshot empty after reset")
    }

    /**
     * 并发安全:100 个协程同时 increment 同一 (label, category),最终计数 == 100。
     *
     * 用 `withContext(Dispatchers.Default)` 切到真实线程池,绕开 runTest 的虚拟调度器,
     * 才能真正暴露竞争条件。
     */
    @Test
    fun concurrent_100_coroutines_same_label_increments_to_100() = runTest {
        ErrorMetrics.reset()
        val label = "concurrent"
        val cat = ErrorCategory.Transient
        val n = 100
        withContext(Dispatchers.Default) {
            coroutineScope {
                val jobs = (1..n).map {
                    async {
                        ErrorMetrics.increment(label, cat)
                    }
                }
                jobs.awaitAll()
            }
        }
        val snap = ErrorMetrics.snapshot()
        assertEquals(n.toLong(), snap[label to cat], "concurrent increments lost; got snap=$snap")
    }

    /**
     * 并发不同 label:100 个协程 increment 100 个不同 label,每个 label 计数 == 1。
     * 验证 mutex 串行不会把 increment 串到错的 key 上。
     */
    @Test
    fun concurrent_distinct_labels_each_counted_once() = runTest {
        ErrorMetrics.reset()
        val n = 100
        withContext(Dispatchers.Default) {
            coroutineScope {
                val jobs = (1..n).map { idx ->
                    async {
                        ErrorMetrics.increment("label-$idx", ErrorCategory.Internal)
                    }
                }
                jobs.awaitAll()
            }
        }
        val snap = ErrorMetrics.snapshot()
        assertEquals(n, snap.size, "expected $n distinct entries, got ${snap.size}")
        assertTrue(snap.values.all { it == 1L }, "expected each counter == 1, got values=${snap.values}")
    }
}

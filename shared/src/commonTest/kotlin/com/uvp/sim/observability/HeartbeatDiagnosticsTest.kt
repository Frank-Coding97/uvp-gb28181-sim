package com.uvp.sim.observability

import com.uvp.sim.api.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * cross-review R2 #4 覆盖:iOS 诊断心跳 + watchdog 行为测试。
 *
 * 心跳 loop 和 watchdog 判定层从 iosMain 抽到 commonMain 后,
 * TestScope + advanceTimeBy 推虚拟时间,不真等 30s,可以在 shared jvmTest 完整跑。
 * iosMain 的 IosAppHost 只保留 dispatch_async 主线程探测这类 iOS 特有 API,
 * 那部分留 CI 阶段的 iosSimulatorArm64Test / 真机手测。
 */

/** 测试用可变 counter,模拟 SystemLogger.emitCount / IosAppHost.recomposeCount 。 */
private class FakeCounters(
    var emit: Long = 0L,
    var recompose: Long = 0L,
    var bufferSize: Int = 0,
) : HeartbeatCounters {
    override val emitCount: Long get() = emit
    override val recomposeCount: Long get() = recompose
    override val logBufferSize: Int get() = bufferSize
}

private data class RecordedLog(val level: LogLevel, val tag: LogTag, val message: String)

private class RecordingSink {
    val samples: MutableList<HeartbeatSample> = mutableListOf()
    val warns: MutableList<HeartbeatSample> = mutableListOf()

    fun onSample(): (HeartbeatSample) -> Unit = { samples += it }
    fun onWarn(): (HeartbeatSample) -> Unit = { warns += it }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatDiagnosticsTest {

    // --- 纯判定函数 ------------------------------------------------------

    @Test
    fun computeHeartbeatSample_normal_traffic_no_storm() {
        val sample = computeHeartbeatSample(
            prevEmit = 0L,
            prevRecompose = 0L,
            curEmit = 60L,
            curRecompose = 30L,
            logBufferSize = 60,
            elapsedMillis = 30_000L,
        )
        assertEquals(2L, sample.emitRate, "60 条 / 30s = 2/s")
        assertEquals(1L, sample.recomposeRate)
        assertFalse(sample.emitStorm)
        assertFalse(sample.recomposeStorm)
        assertFalse(sample.recomposeStalled)
    }

    @Test
    fun computeHeartbeatSample_emit_storm_triggers_flag() {
        val sample = computeHeartbeatSample(
            prevEmit = 0L,
            prevRecompose = 100L,
            curEmit = 30_000L, // 30s 内 30k 条 = 1000/s,阈值 500/s
            curRecompose = 130L,
            logBufferSize = 1000,
            elapsedMillis = 30_000L,
        )
        assertTrue(sample.emitStorm, "1000/s 应触发日志风暴")
        assertFalse(sample.recomposeStorm)
    }

    @Test
    fun computeHeartbeatSample_recompose_stalled_when_no_growth() {
        val sample = computeHeartbeatSample(
            prevEmit = 100L,
            prevRecompose = 500L,
            curEmit = 130L, // 日志还在跑
            curRecompose = 500L, // 但 UI 没重组过 —— 主线程卡死
            logBufferSize = 130,
            elapsedMillis = 30_000L,
        )
        assertTrue(sample.recomposeStalled, "recompose 不动应报 stalled")
        assertFalse(sample.emitStorm)
    }

    @Test
    fun computeHeartbeatSample_handles_zero_elapsed_without_div_zero() {
        // 极端场景:clock 退化 / 精度问题导致 elapsedMillis=0,不应 crash
        val sample = computeHeartbeatSample(
            prevEmit = 0L,
            prevRecompose = 0L,
            curEmit = 10L,
            curRecompose = 5L,
            logBufferSize = 10,
            elapsedMillis = 0L,
        )
        // 内部 coerceAtLeast(1) 保底,不抛异常即可
        assertTrue(sample.emitRate > 0)
    }

    @Test
    fun computeHeartbeatSample_negative_counter_delta_clamped() {
        // 极端场景:计数器意外回退(比如 process restart / atomicity 疏漏)
        // 不应算出负速率,应 clamp 到 0。
        val sample = computeHeartbeatSample(
            prevEmit = 1000L,
            prevRecompose = 100L,
            curEmit = 500L, // 回退了
            curRecompose = 90L, // 回退了
            logBufferSize = 500,
            elapsedMillis = 30_000L,
        )
        assertEquals(0L, sample.emitRate)
        assertEquals(0L, sample.recomposeRate)
    }

    // --- heartbeatLoop 循环行为(TestScope + advanceTimeBy)------------------

    @Test
    fun heartbeatLoop_normal_scenario_emits_samples_without_warn() = runTest {
        val counters = FakeCounters(emit = 0L, recompose = 0L)
        val sink = RecordingSink()
        val scheduler = testScheduler

        val job = launch {
            heartbeatLoop(
                counters = counters,
                intervalMillis = 30_000L,
                clock = { scheduler.currentTime },
                onSample = sink.onSample(),
                onWarn = sink.onWarn(),
            )
        }

        // 模拟正常业务:每 30s 有 60 条日志 + 30 次重组
        repeat(3) {
            advanceTimeBy(30_000L)
            counters.emit += 60L
            counters.recompose += 30L
            runCurrent()
        }

        job.cancelAndJoin()

        assertEquals(3, sink.samples.size, "3 个采样窗口")
        sink.samples.forEach { s ->
            assertFalse(s.emitStorm)
            assertFalse(s.recomposeStorm)
            assertFalse(s.recomposeStalled)
        }
        assertTrue(sink.warns.isEmpty(), "正常心跳不应触发 warn")
    }

    @Test
    fun heartbeatLoop_log_storm_triggers_warn() = runTest {
        val counters = FakeCounters()
        val sink = RecordingSink()
        val scheduler = testScheduler

        val job = launch {
            heartbeatLoop(
                counters = counters,
                intervalMillis = 30_000L,
                clock = { scheduler.currentTime },
                onSample = sink.onSample(),
                onWarn = sink.onWarn(),
            )
        }

        // 日志风暴:30s 内涨 30_000 条 = 1000/s,远超默认阈值 500/s
        // 同时 recompose 保持正常增长,单独触发 emit 风暴信号
        counters.recompose = 30L
        advanceTimeBy(30_000L)
        counters.emit = 30_000L
        counters.recompose = 60L
        runCurrent()

        job.cancelAndJoin()

        assertEquals(1, sink.samples.size)
        val storm = sink.samples.single()
        assertTrue(storm.emitStorm, "日志风暴应触发 emitStorm=true")
        assertFalse(storm.recomposeStorm, "recompose 正常不应触发")
        assertEquals(1, sink.warns.size, "onWarn 应被调用一次,让 SystemLogger 走 Warning 级日志")
    }

    @Test
    fun heartbeatLoop_main_thread_stall_triggers_warn() = runTest {
        val counters = FakeCounters(emit = 0L, recompose = 100L)
        val sink = RecordingSink()
        val scheduler = testScheduler

        val job = launch {
            heartbeatLoop(
                counters = counters,
                intervalMillis = 30_000L,
                clock = { scheduler.currentTime },
                onSample = sink.onSample(),
                onWarn = sink.onWarn(),
            )
        }

        // 日志还在跑,但重组计数完全不动 —— 主线程被阻塞
        repeat(2) {
            advanceTimeBy(30_000L)
            counters.emit += 60L
            // counters.recompose 保持 100 不变
            runCurrent()
        }

        job.cancelAndJoin()

        assertEquals(2, sink.samples.size)
        sink.samples.forEach { s ->
            assertTrue(s.recomposeStalled, "recompose 停滞应报 stalled")
            assertFalse(s.emitStorm)
        }
        assertEquals(2, sink.warns.size, "每个停滞窗口都应触发 warn")
    }

    @Test
    fun heartbeatLoop_can_be_started_multiple_times_without_leak() = runTest {
        // 模拟 bindLogger 被反复调用(比如 UI 重挂载)的场景。
        // IosAppHost 的做法是 job?.cancel() 再 launch,这里断言:
        //   1. cancel 前一个 job 后再起,新 loop 不会漏采样
        //   2. 不会重复采样 / 不会双 emit
        val counters = FakeCounters()
        val sink1 = RecordingSink()
        val sink2 = RecordingSink()
        val scheduler = testScheduler

        var job: Job? = null

        // 第一次 bindLogger 相当于起 loop
        job = launch {
            heartbeatLoop(
                counters = counters,
                intervalMillis = 30_000L,
                clock = { scheduler.currentTime },
                onSample = sink1.onSample(),
                onWarn = sink1.onWarn(),
            )
        }

        advanceTimeBy(30_000L)
        counters.emit = 30L
        counters.recompose = 15L
        runCurrent()
        assertEquals(1, sink1.samples.size)

        // 模拟"再次 bindLogger":老 job 先 cancel,再起新 loop
        job.cancelAndJoin()
        // 新 loop 用独立 sink,验证老 sink 不再收到采样
        val job2 = launch {
            heartbeatLoop(
                counters = counters,
                intervalMillis = 30_000L,
                clock = { scheduler.currentTime },
                onSample = sink2.onSample(),
                onWarn = sink2.onWarn(),
            )
        }

        advanceTimeBy(30_000L)
        counters.emit = 60L
        counters.recompose = 30L
        runCurrent()

        job2.cancelAndJoin()

        assertEquals(1, sink1.samples.size, "老 sink 停在第一次采样,不再增长")
        assertEquals(1, sink2.samples.size, "新 sink 有独立的一次采样")
        assertFalse(sink2.samples.single().emitStorm)
    }

    @Test
    fun heartbeatLoop_rejects_non_positive_interval() = runTest {
        val counters = FakeCounters()
        val scheduler = testScheduler
        var thrown: IllegalArgumentException? = null
        try {
            heartbeatLoop(
                counters = counters,
                intervalMillis = 0L,
                clock = { scheduler.currentTime },
                onSample = {},
                onWarn = {},
            )
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertTrue(thrown != null, "intervalMillis=0 应快速失败")
    }

    // --- 主线程 watchdog 纯判定 ---------------------------------------------

    @Test
    fun evaluateMainThreadWatchdog_ack_arrived_no_stall() {
        val decision = evaluateMainThreadWatchdog(
            probeSentAtMs = 100L,
            ackAtMs = 150L, // ack > probeSentAt,主线程活着
            nowMs = 200L,
            state = WatchdogState(),
        )
        assertFalse(decision.stalled)
        assertEquals(50L, decision.ackLagMs, "now - ack = 200-150 = 50")
    }

    @Test
    fun evaluateMainThreadWatchdog_ack_missing_reports_stall() {
        val decision = evaluateMainThreadWatchdog(
            probeSentAtMs = 100L,
            ackAtMs = 50L, // ack < probeSentAt,主线程没回来 ack
            nowMs = 200L,
            state = WatchdogState(),
        )
        assertTrue(decision.stalled)
        assertEquals(200L, decision.newState.lastStallLogMs, "触发 stall 后应记录 now,启动冷却")
    }

    @Test
    fun evaluateMainThreadWatchdog_never_acked_yields_max_lag() {
        val decision = evaluateMainThreadWatchdog(
            probeSentAtMs = 100L,
            ackAtMs = -1L, // 从没 ack 过
            nowMs = 200L,
            state = WatchdogState(),
        )
        assertTrue(decision.stalled)
        assertEquals(Long.MAX_VALUE, decision.ackLagMs, "从未 ack 应报 MAX_VALUE lag")
    }

    @Test
    fun evaluateMainThreadWatchdog_cooldown_prevents_log_spam() {
        val first = evaluateMainThreadWatchdog(
            probeSentAtMs = 100L,
            ackAtMs = 50L,
            nowMs = 200L,
            state = WatchdogState(),
            stallCooldownMs = 10_000L,
        )
        assertTrue(first.stalled)

        // 5 秒后再次探测,还是 stall,但冷却期内不再报
        val second = evaluateMainThreadWatchdog(
            probeSentAtMs = 5_000L,
            ackAtMs = 50L,
            nowMs = 5_200L,
            state = first.newState,
            stallCooldownMs = 10_000L,
        )
        assertFalse(second.stalled, "冷却期内不重复报")

        // 冷却期过后应再次触发
        val third = evaluateMainThreadWatchdog(
            probeSentAtMs = 15_000L,
            ackAtMs = 50L,
            nowMs = 15_200L,
            state = first.newState,
            stallCooldownMs = 10_000L,
        )
        assertTrue(third.stalled, "冷却期过后应重新报 stall")
    }
}

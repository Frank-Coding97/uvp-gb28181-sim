package com.uvp.sim.recording

import com.uvp.sim.gb28181.RecordSchedule
import com.uvp.sim.gb28181.RecordTimeSegment
import com.uvp.sim.gb28181.VideoRecordPlanState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RecordPlanScheduler] 的行为契约。
 *
 * 这里锁定的是三条"看起来才像设备"的边界：**只停自己起的**、**不抢占手动录像**、
 * **时段内被外部停止后不反复拉起**。任何一条退化，现场看到的就是
 * 「录像被莫名停掉」或「停止按钮失灵」。
 *
 * 时间全部走虚拟时钟（`runTest` + 注入 clock），不靠真等待 —— 否则这个测试类要跑好几分钟。
 */
class RecordPlanSchedulerTest {

    private fun seg(sh: Int, eh: Int) = RecordTimeSegment(sh, 0, 0, eh, 0, 0)

    private fun plan(
        enable: Int = 1,
        schedules: List<RecordSchedule> = listOf(
            RecordSchedule(weekDayNum = 1, segments = listOf(seg(8, 12)))
        ),
    ) = VideoRecordPlanState(recordEnable = enable, schedules = schedules, streamNumber = 0)

    /** 周一 09:00 —— 落在 08:00-12:00 这个段里。 */
    private val monday0900 = LocalClockReading(weekday = 1, secondsOfDay = 9 * 3600)

    private class Fake(
        var plan: VideoRecordPlanState,
        var state: RecordingState = RecordingState.Idle,
        var now: LocalClockReading,
    ) {
        val starts = mutableListOf<Int>()
        var stops = 0

        fun startSchedules(streamNumber: Int): Result<Unit> {
            starts += streamNumber
            state = RecordingState.Recording(0L, 0, RecordSource.Schedule)
            return Result.success(Unit)
        }

        fun stop(): Result<RecordingFile?> {
            stops += 1
            state = RecordingState.Idle
            return Result.success(null)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.schedulerFor(fake: Fake) = RecordPlanScheduler(
        scope = backgroundScope,
        planProvider = { fake.plan },
        recordingState = { fake.state },
        onStart = { streamNumber -> fake.startSchedules(streamNumber) },
        onStop = { fake.stop() },
        clock = { fake.now },
        tickIntervalMs = 1_000L,
    )

    @Test
    fun starts_recording_when_segment_is_active() = runTest {
        val fake = Fake(plan = plan(), now = monday0900)
        schedulerFor(fake).start()

        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(listOf(0), fake.starts, "时段内且没在录 → 应当以 Schedule 来源起录")
        assertEquals(RecordSource.Schedule, (fake.state as RecordingState.Recording).source)
    }

    @Test
    fun stops_recording_when_segment_ends() = runTest {
        val fake = Fake(plan = plan(), now = monday0900)
        val sched = schedulerFor(fake)
        sched.start()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, fake.starts.size)

        // 12:30 —— 已离开时段
        fake.now = LocalClockReading(weekday = 1, secondsOfDay = 12 * 3600 + 30 * 60)
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(1, fake.stops, "时段结束后应当停掉「计划起的」那一段")
    }

    @Test
    fun does_not_preempt_manual_recording() = runTest {
        val fake = Fake(
            plan = plan(),
            state = RecordingState.Recording(0L, 0, RecordSource.Manual),
            now = monday0900,
        )
        val sched = schedulerFor(fake)
        sched.start()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(0, fake.starts.size, "用户正在手动录，计划不能抢摄像头")

        // 时段结束后也不许停别人那一段
        fake.now = LocalClockReading(weekday = 1, secondsOfDay = 13 * 3600)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(0, fake.stops, "计划只停自己起的录像，手动那段不许碰")
    }

    @Test
    fun does_not_restart_after_external_stop_within_same_segment() = runTest {
        val fake = Fake(plan = plan(), now = monday0900)
        val sched = schedulerFor(fake)
        sched.start()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, fake.starts.size)

        // 模拟用户点了「停止」：状态回到 Idle（调度器没发起过 stop，所以 stops 仍是 0）
        fake.state = RecordingState.Idle
        repeats(3) {
            advanceTimeBy(1_500)
            runCurrent()
        }

        assertEquals(
            1, fake.starts.size,
            "本时段被人工停止后不能再自动拉起 —— 否则界面看起来像「停止按钮失灵」",
        )
        assertEquals(0, fake.stops)
    }

    @Test
    fun does_not_start_when_plan_disabled() = runTest {
        val fake = Fake(plan = plan(enable = 0), now = monday0900)
        val sched = schedulerFor(fake)
        sched.start()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(0, fake.starts.size, "RecordEnable=0 时任何时刻都不起录")
    }

    @Test
    fun resumption_happens_at_next_segment() = runTest {
        val fake = Fake(
            plan = plan(
                schedules = listOf(
                    RecordSchedule(weekDayNum = 1, segments = listOf(seg(8, 12), seg(14, 18)))
                )
            ),
            now = LocalClockReading(weekday = 1, secondsOfDay = 12 * 3600 + 30 * 60),
        )
        val sched = schedulerFor(fake)
        sched.start()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(0, fake.starts.size, "两段之间的空档不录")

        fake.now = LocalClockReading(weekday = 1, secondsOfDay = 14 * 3600 + 60)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, fake.starts.size, "进入下一段要照常起录")
    }

    private inline fun repeats(times: Int, block: () -> Unit) {
        for (i in 0 until times) block()
    }
}

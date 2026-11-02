package com.uvp.sim.recording

import com.uvp.sim.gb28181.VideoRecordPlanState
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 按本机录像计划（GB/T 28181-2022 A.2.1.15）起停录像的调度器。
 *
 * ## 它做什么
 *
 * 每 [tickIntervalMs] 看一次「现在该不该录」：
 *   - 命中某个时段且当前没在录 → 以 [RecordSource.Schedule] 起录
 *   - 不在任何时段，而当前这段录像是**计划自己起的** → 停录
 *   - 期间有人在录（手动 / 平台 `RecordCmd`）→ **不抢占、不插手**
 *
 * ## ⛔ 三个刻意的行为，都不是疏漏
 *
 * **1. 只停自己起的。** 判断依据是 `RecordingState.Recording.source == Schedule`。
 *    用户手动录着的时候计划到点不抢摄像头；计划结束也不去停用户那段 —— 停的要是别人的
 *    录像，"录像列表里莫名多/少一段"就再也解释不清了。
 *
 * **2. 时段内被外力停掉，本段不再自动拉起（抑制）。** 用户点停止 → 下一个 tick 立刻又
 *    自动开录，界面会像"按钮失灵"。所以一旦观察到计划录像**在时段内**消失（不是我们自己
 *    停的），就把这一段的 `key` 记进抑制表，直到离开该时段才解除。跨天（本地秒数变小）
 *    也会强制解除，避免"上周一同样的 08:00-12:00 莫名其妙不录像"。
 *
 * **3. 失败退避。** 起录失败（磁盘满、权限、摄像头被占）后 60 秒内不再重试。
 *    否则每个 tick 都失败一次 = 每 10 秒刷一条错误日志，日志面板直接把有用的信息挤没。
 *
 * ## 生命周期边界（说清楚，免得当 bug 报）
 *
 *  - 靠 [start] 挂到 App 的协程作用域上，**进程在跑才调度**。App 被系统杀掉后不会像真 IPC
 *    那样自己开机重录 —— 装回 App 后会在下一个 tick 自愈（只要还在时段内）。
 *  - 计划录像走的是与手动录像完全相同的 `RecordingService.start` 路径，所以同样要求
 *    「画面源在采集」（首页预览/推流任一在跑）。模拟器只有手机这一路真实画面，不在范围内
 *    的"设备独自开机录一天"不具备条件。
 *  - Doze / 后台限制会让 tick 变慢，实际起停误差可能大于 [tickIntervalMs]。前台服务在录像
 *    期间会拉住进程，起录之后不受影响；只有"到点那一刻"可能被推迟。
 *
 * 线程模型：全部逻辑跑在 [scope] 里，单协程串行，[planProvider] / [recordingState] 只需读
 * `StateFlow.value`（线程安全）即可。
 */
class RecordPlanScheduler(
    private val scope: CoroutineScope,
    private val planProvider: () -> VideoRecordPlanState,
    private val recordingState: () -> RecordingState,
    private val onStart: suspend (streamNumber: Int) -> Result<Unit>,
    private val onStop: suspend () -> Result<RecordingFile?>,
    private val clock: () -> LocalClockReading = { LocalWallClock.now() },
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
) {
    private var job: Job? = null

    /** 手动唤醒通道：保存计划 / 录像被外部停止时用它，不必等下一个 tick。 */
    private val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 上一个 tick 是否观察到一个「由计划起的」录像在跑。 */
    private var sawPlanRecording = false

    /** 本次停止是调度器自己发起的 —— 用来把"到点停"和"被人工停"区分开。 */
    private var selfStopRequested = false

    /** 被抑制的时段 key + 发生时的本地秒数（跨天判定用）。 */
    private var suppressedSlotKey: String? = null
    private var suppressedAtSecondsOfDay: Int = 0

    /** 启动失败退避：上次失败的 tick 序号。 */
    private var lastFailureTick: Long = Long.MIN_VALUE / 2
    private var tickCount: Long = 0

    /** 幂等：重复调用不会起第二个循环。 */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                tickCount += 1
                runCatching { tick() }.onFailure {
                    SystemLogger.emit(LogLevel.Warning, LogTag.Media, "录像计划调度异常：${it.message}")
                }
                // 有 kick 就立刻进下一轮，否则等满一个 tick
                withTimeoutOrNull(tickIntervalMs) { kicks.first() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * 立刻重算一次（不等 tick）。
     *
     * 用途：用户在页面保存了新计划、或从录像列表手动停掉一段后，让行为与配置立刻一致。
     */
    fun kick() {
        kicks.tryEmit(Unit)
    }

    private suspend fun tick() {
        val now = clock()
        val plan = planProvider()
        val slot = RecordPlanEvaluator.activeSlot(plan, now)
        val planRecording = isScheduleRecording(recordingState())

        expireSuppression(slot, now)

        // ① 上一轮还在按计划录，这一轮不在了，而且不是我们自己停的 → 外力（用户/平台）停的
        if (sawPlanRecording && !planRecording && !selfStopRequested) {
            val key = slot?.key
            if (key != null) {
                suppressedSlotKey = key
                suppressedAtSecondsOfDay = now.secondsOfDay
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "计划录像被外部停止，本时段（${slot.label}）不再自动恢复；下个时段照常开录",
                )
            }
        }
        selfStopRequested = false
        sawPlanRecording = planRecording

        // ② 不在任何时段：只停"计划自己起的"那一段
        if (slot == null) {
            if (planRecording) {
                selfStopRequested = true
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "计划时段结束，停止录像")
                runCatching { onStop() }.onFailure {
                    SystemLogger.emit(LogLevel.Warning, LogTag.Media, "计划停止录像失败：${it.message}")
                }
            }
            return
        }

        // ③ 本时段被人工中止过 → 本次不再拉起
        if (suppressedSlotKey == slot.key) return

        // ④ 已有录像在跑（手动 / 平台 / 计划）→ 不抢占。Stopping 也算"在跑"，
        //    否则刚好在写文件尾的那一瞬会被判定成"没人录"，立刻又起一段导致文件互相覆盖。
        val stateNow = recordingState()
        if (stateNow is RecordingState.Recording || stateNow is RecordingState.Stopping) {
            sawPlanRecording = sawPlanRecording || isScheduleRecording(stateNow)
            return
        }

        // ⑤ 失败退避
        if (stateNow is RecordingState.Failed && tickCount - lastFailureTick < FAILURE_BACKOFF_TICKS) return

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "计划时段开始（${slot.label}），开始录像：StreamNumber=${plan.streamNumber}",
        )
        val result = runCatching { onStart(plan.streamNumber) }
            .getOrElse { Result.failure(it) }
        if (result.isFailure) {
            lastFailureTick = tickCount
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "计划录像启动失败：${result.exceptionOrNull()?.message ?: "unknown"}",
            )
        } else {
            sawPlanRecording = true
        }
    }

    /**
     * 清抑制：离开本时段，或者跨天（本地秒数回退了）。
     *
     * ⛔ 跨天判断不能省：抑制 key 只含「周几 + 时段」，下周一同一时段的 key 与今天完全相同，
     * 不按时间清就会出现「上周手动停过一次，这周一莫名不录了」。
     */
    private fun expireSuppression(slot: ActiveRecordPlanSlot?, now: LocalClockReading) {
        val key = suppressedSlotKey ?: return
        if (slot == null || slot.key != key) {
            suppressedSlotKey = null
            return
        }
        if (now.secondsOfDay < suppressedAtSecondsOfDay) {
            suppressedSlotKey = null
        }
    }

    private fun isScheduleRecording(state: RecordingState): Boolean = when (state) {
        is RecordingState.Recording -> state.source == RecordSource.Schedule
        is RecordingState.Stopping -> state.previous.source == RecordSource.Schedule
        else -> false
    }

    companion object {
        /**
         * 默认 10 秒一次。
         *
         * 为什么不是 1 秒：计划精度是分钟级（页面按 时/分/秒 输，现场基本只填分钟），
         * 10 秒误差已经远小于人的感知；而 1 秒一次等于闲着也每秒唤醒 —— 手机上是真实的耗电。
         * 也不做成"精确睡到下一个边界"：那样每次计划变更都得重算唤醒时刻，代码换来的是
         * 秒级精度，代价不成比例。
         */
        const val DEFAULT_TICK_INTERVAL_MS: Long = 10_000L

        /** 60 秒退避（按默认 tick 折算 6 轮）。 */
        private const val FAILURE_BACKOFF_TICKS: Long = 6
    }
}

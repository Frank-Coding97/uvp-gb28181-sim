package com.uvp.sim.observability

import com.uvp.sim.api.LogTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

/**
 * iOS 诊断心跳 + 主线程 watchdog 的纯逻辑层。
 *
 * 背景(cross-review R2 #4):`IosAppHost` 里心跳 loop 直接读 `SystemLogger.emitCount` /
 * `recomposeCount` 判断日志风暴 / 重组风暴 / 主线程阻塞,整条观测链路以前无测试。
 * 心跳一旦静默失效,CI 不会暴露。
 *
 * 这里把判定和 loop 抽成 commonMain 纯逻辑(依赖注入 counter / clock / sink),
 * iosMain 的 `IosAppHost` 只保留 dispatch_async 主线程探测这类 iOS 特有 API。
 * commonTest 用 `kotlinx.coroutines.test.TestScope + advanceTimeBy` 推进虚拟时间,
 * 不需要真跑 30 秒。
 */

/** 心跳读的计数器抽象,产品代码里由 SystemLogger.emitCount + IosAppHost.recomposeCount 实现。 */
interface HeartbeatCounters {
    val emitCount: Long
    val recomposeCount: Long
    val logBufferSize: Int
}

/** 心跳落地输出。生产环境走 SystemLogger.emit,测试里收集日志断言。 */
fun interface HeartbeatSink {
    fun emit(level: LogLevel, tag: LogTag, message: String)
}

/** 单次心跳采样结果 —— 纯数据,方便测试单独断言。 */
data class HeartbeatSample(
    val elapsedSeconds: Double,
    val emitRate: Long,
    val recomposeRate: Long,
    val emitCount: Long,
    val recomposeCount: Long,
    val logBufferSize: Int,
    val emitStorm: Boolean,
    val recomposeStorm: Boolean,
    val recomposeStalled: Boolean,
)

/** 心跳判定阈值。刻意暴露成可注入,方便测试用小值触发对应场景。 */
data class HeartbeatThresholds(
    /** emitRate 超过多少视为日志风暴。生产默认 500/s(30s 窗口 = 15000 条)。 */
    val emitStormPerSec: Long = 500L,
    /** recomposeRate 超过多少视为重组风暴。 */
    val recomposeStormPerSec: Long = 200L,
    /** recomposeRate 是否停滞 —— UI 挂载后长期 0 视为主线程停摆。 */
    val recomposeStallPerSec: Long = 0L,
) {
    init {
        require(emitStormPerSec > 0) { "emitStormPerSec must be > 0" }
        require(recomposeStormPerSec > 0) { "recomposeStormPerSec must be > 0" }
        require(recomposeStallPerSec >= 0) { "recomposeStallPerSec must be >= 0" }
    }
}

/** 心跳判定纯函数。输入前后计数 + 时间差,输出采样 + 三种警报标记。 */
fun computeHeartbeatSample(
    prevEmit: Long,
    prevRecompose: Long,
    curEmit: Long,
    curRecompose: Long,
    logBufferSize: Int,
    elapsedMillis: Long,
    thresholds: HeartbeatThresholds = HeartbeatThresholds(),
): HeartbeatSample {
    val elapsedSec = (elapsedMillis.coerceAtLeast(1L)) / 1000.0
    val emitDelta = (curEmit - prevEmit).coerceAtLeast(0L)
    val recomposeDelta = (curRecompose - prevRecompose).coerceAtLeast(0L)
    val emitRate = (emitDelta / elapsedSec).toLong()
    val recomposeRate = (recomposeDelta / elapsedSec).toLong()
    return HeartbeatSample(
        elapsedSeconds = elapsedSec,
        emitRate = emitRate,
        recomposeRate = recomposeRate,
        emitCount = curEmit,
        recomposeCount = curRecompose,
        logBufferSize = logBufferSize,
        emitStorm = emitRate > thresholds.emitStormPerSec,
        recomposeStorm = recomposeRate > thresholds.recomposeStormPerSec,
        // 停滞判定要求心跳启动至少一段时间(elapsedMillis>0),否则冷启会误报。
        recomposeStalled = recomposeRate <= thresholds.recomposeStallPerSec && curRecompose == prevRecompose,
    )
}

/**
 * 心跳 loop 容器 —— suspend + `while (isActive)`,靠 `delay(intervalMillis)` 推进。
 * TestScope 会把 delay 折成虚拟时间,不真等 30s。
 *
 * @param onSample 采样回调,产品代码里拼诊断字符串写 SystemLogger.emit(Debug,...)
 * @param onWarn 风暴/停滞警报,产品代码里走 SystemLogger.emit(Warning,...)。这个是 R2 #4 的关键补丁:
 *               以前心跳只在 Debug 级别输出,链路失效时没有 warn 信号可让告警系统抓;抽出后测试可以断言。
 */
suspend fun heartbeatLoop(
    counters: HeartbeatCounters,
    intervalMillis: Long = 30_000L,
    thresholds: HeartbeatThresholds = HeartbeatThresholds(),
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    onSample: (HeartbeatSample) -> Unit,
    onWarn: (HeartbeatSample) -> Unit = {},
) {
    require(intervalMillis > 0L) { "intervalMillis must be > 0" }
    var prevEmit = counters.emitCount
    var prevRecompose = counters.recomposeCount
    var prevTick = clock()
    // 首次进 loop 就 delay 一个窗口,跟原实现语义对齐(避免立刻发一次空采样)。
    while (coroutineContext.isActive) {
        try {
            delay(intervalMillis)
        } catch (e: CancellationException) {
            throw e
        }
        val nowTick = clock()
        val sample = computeHeartbeatSample(
            prevEmit = prevEmit,
            prevRecompose = prevRecompose,
            curEmit = counters.emitCount,
            curRecompose = counters.recomposeCount,
            logBufferSize = counters.logBufferSize,
            elapsedMillis = nowTick - prevTick,
            thresholds = thresholds,
        )
        prevEmit = sample.emitCount
        prevRecompose = sample.recomposeCount
        prevTick = nowTick
        onSample(sample)
        if (sample.emitStorm || sample.recomposeStorm || sample.recomposeStalled) {
            onWarn(sample)
        }
    }
}

/**
 * 主线程 watchdog 的纯判定:probeSentAt → ack。
 * ack < probeSentAt 说明主线程还没回来 ack,判为 stall。
 * @param stallCooldownMs 触发一次 stall 日志后,冷却窗内不再重复报,防日志刷屏。
 */
data class WatchdogState(val lastStallLogMs: Long = -1L)

data class WatchdogDecision(val stalled: Boolean, val ackLagMs: Long, val newState: WatchdogState)

fun evaluateMainThreadWatchdog(
    probeSentAtMs: Long,
    ackAtMs: Long,
    nowMs: Long,
    state: WatchdogState,
    stallCooldownMs: Long = 10_000L,
): WatchdogDecision {
    val ackLagMs = if (ackAtMs > 0L) nowMs - ackAtMs else Long.MAX_VALUE
    val ackMissing = ackAtMs < probeSentAtMs
    val cooldownPassed = state.lastStallLogMs < 0L || nowMs - state.lastStallLogMs >= stallCooldownMs
    val stalled = ackMissing && cooldownPassed
    val newState = if (stalled) state.copy(lastStallLogMs = nowMs) else state
    return WatchdogDecision(stalled = stalled, ackLagMs = ackLagMs, newState = newState)
}

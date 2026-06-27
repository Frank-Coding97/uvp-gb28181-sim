package com.uvp.sim.sip

import com.uvp.sim.observability.ErrorCategory
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * M-2 (audit §3) — SIP dialog 空闲超时 + GC。
 *
 * 之前 INVITE / SUBSCRIBE dialog 状态由各自 Coordinator 维护,异常通路(对端崩 /
 * 网络断开但 socket 没失败)产生的 dangling dialog 永远不会被回收。本类提供
 * 中性的"心跳 + 周期 GC"基础设施,让 Coordinator 在 dialog 建立/收到消息时
 * 调 [touch],定期由 [start] 启动的协程扫并执行 [onStale] 回调。
 *
 * 设计要点:
 * - 全部状态在 [mutex] 保护下读写,跨协程安全
 * - [nowMs] / [scope] 都可注入,单测用 TestScope + virtual time 推进
 * - GC 周期默认 60s,空闲阈值默认 1800s(30 分钟,SimConfig 可改)
 * - onStale 回调在 [scope] 协程内顺序触发,Coord 收到后自行做 BYE / close
 */
internal class SipDialogIdleTracker(
    private val scope: CoroutineScope,
    private val idleMs: Long,
    private val gcIntervalMs: Long = 60_000L,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onStale: suspend (dialogId: String, lastActivityMs: Long) -> Unit,
) {
    private val mutex = Mutex()
    private val lastSeen = mutableMapOf<String, Long>()
    private var gcJob: Job? = null

    /** 业务侧调用 — dialog 建立或收到 in-dialog 消息时刷新时间戳。 */
    suspend fun touch(dialogId: String) = mutex.withLock {
        lastSeen[dialogId] = nowMs()
    }

    /** dialog 主动 BYE / cancel 时移除 — 避免 stale 通知再发。 */
    suspend fun forget(dialogId: String) = mutex.withLock {
        lastSeen.remove(dialogId)
    }

    /** 当前活跃 dialog 数量,UI / 单测可见。 */
    suspend fun activeCount(): Int = mutex.withLock { lastSeen.size }

    /**
     * 立即扫一次 stale dialog 并触发 [onStale]。返回触发的 dialog id 集合
     * (单测断言用)。
     */
    suspend fun gcOnce(): List<String> {
        val now = nowMs()
        val stale = mutex.withLock {
            val toRemove = lastSeen.filter { (_, t) -> now - t >= idleMs }.keys.toList()
            toRemove.forEach { lastSeen.remove(it) }
            toRemove
        }
        if (stale.isNotEmpty()) {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Lifecycle,
                "SIP dialog 空闲超时 GC: ${stale.size} 个 dialog 被清理 (idle≥${idleMs / 1000}s)",
                category = ErrorCategory.Transient,
            )
            for (id in stale) {
                runCatching { onStale(id, now) }
            }
        }
        return stale
    }

    /** 启动周期 GC 协程。重复调用 idempotent — 旧 job 取消后再启。 */
    fun start() {
        gcJob?.cancel()
        gcJob = scope.launch {
            while (isActive) {
                delay(gcIntervalMs)
                runCatching { gcOnce() }
            }
        }
    }

    fun stop() {
        gcJob?.cancel()
        gcJob = null
    }
}

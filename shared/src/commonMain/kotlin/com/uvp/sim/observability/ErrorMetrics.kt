package com.uvp.sim.observability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P3-3 (audit §4.5 / Wave 5 PR-OBSERVABILITY) — 错误计数器,进程级单例。
 *
 * 由 [com.uvp.sim.domain.UserErrorMapper.map] 内部自动调用,业务代码 *不* 直接调
 * [increment](误调风险:漏 / 重 / label 拼错)。
 *
 * ## 不用 atomicfu / kotlin.concurrent.atomics 的原因
 *
 * Kotlin 2.1.0 没暴露 `kotlin.concurrent.atomics`(Wave 2 PR-SN-IDENTITY 已踩过),
 * 引入 atomicfu 又得加 plugin + 编译期注入,代码量 ≪ 收益。
 *
 * **方案**:Mutex.withLock 串行 Map 读写。
 *
 * - increment 频率:错误才触发,远低于 10/s 量级
 * - 单次 lock 持有时间:Map<Pair<...>, Long> get/put,纳秒级
 * - KMP-safe:`kotlinx.coroutines.sync.Mutex` 全平台都有
 *
 * ## 用法
 *
 * ```kotlin
 * // 一般情况下不用手动调,UserErrorMapper.map 已自动 increment
 * val snap = ErrorMetrics.snapshot()
 * snap.forEach { (key, count) -> println("${key.first}/${key.second}: $count") }
 * ErrorMetrics.reset()  // 测试或用户操作时
 * ```
 */
object ErrorMetrics {

    private val counters = mutableMapOf<Pair<String, ErrorCategory>, Long>()
    private val mutex = Mutex()

    /**
     * 给 `(label, category)` 计数器 +1。
     *
     * label 是调用方传给 UserErrorMapper.map 的 context 串(如 "send INVITE" / "RTP bind"),
     * 设计为低基数 — 不要拼可变值(IP / sn 等),否则 Map 会膨胀。
     */
    suspend fun increment(label: String, category: ErrorCategory) {
        mutex.withLock {
            val key = label to category
            counters[key] = (counters[key] ?: 0L) + 1L
        }
    }

    /**
     * 快照拷贝 — 不可变 Map,UI 拉去渲染用。
     */
    suspend fun snapshot(): Map<Pair<String, ErrorCategory>, Long> =
        mutex.withLock { counters.toMap() }

    /**
     * 清零所有计数器。测试 `@BeforeTest` 或用户"清除统计"按钮触发。
     */
    suspend fun reset() {
        mutex.withLock { counters.clear() }
    }
}

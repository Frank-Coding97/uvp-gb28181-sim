package com.uvp.sim.observability

/**
 * 环形缓冲。容量满后丢最旧。
 *
 * 不直接给 UI 用 — UI 通过 [SystemLogger.snapshot] / [SystemLogger.flow] 拉数据。
 *
 * 线程安全策略: SystemLogger 用单消费者 actor 串行化所有 [add] 调用,
 * snapshot 读到的是 add 当下生效的不可变 List 引用。
 *
 * 为什么不用 MutableSharedFlow(replay=N)?SharedFlow replay 是按订阅时间发的,
 * UI 任意时刻打开需要一次性拉全量历史 — 这是 buffer 的职责。
 */
internal class SystemLogBuffer(private val capacity: Int) {
    private var items: List<SystemLog> = emptyList()

    fun add(log: SystemLog) {
        val current = items
        items = if (current.size >= capacity) {
            current.drop(1) + log
        } else {
            current + log
        }
    }

    fun snapshot(): List<SystemLog> = items
}

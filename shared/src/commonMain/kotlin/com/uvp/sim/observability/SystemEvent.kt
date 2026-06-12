package com.uvp.sim.observability

/**
 * 系统日志级别。Debug < Info < Warning < Error。
 *
 * UI level 阈值过滤:选 X 显示 X 及以上(spec Q8)。
 */
enum class LogLevel(val priority: Int, val short: String) {
    Debug(0, "DBG"),
    Info(1, "INF"),
    Warning(2, "WRN"),
    Error(3, "ERR");

    companion object {
        /** P0 默认阈值 = Info(隐藏 Debug,但保留采集) */
        val Default = Info
    }
}

/**
 * 系统日志分类标签。SIP 故意不在这里 — SIP 事件走 SimEvent,系统日志只记非协议层关注点。
 */
enum class LogTag(val display: String) {
    Network("NETWORK"),
    Media("MEDIA"),
    Lifecycle("LIFECYCLE"),
    Resource("RESOURCE"),
    User("USER"),
    Subscription("SUBSCRIPTION")
}

/**
 * 单条系统日志。
 *
 * - [seq] 单调递增,buffer 排序与暂停期间累积计数靠它(防时钟回拨)
 * - [sessionId] 进每条 log 而不是只在顶部展示,便于多会话拼合后区分
 * - [detail] 可选长文本(stack / 完整报文等),行展开时显示
 */
data class SystemLog(
    val seq: Long,
    val timestampMs: Long,
    val sessionId: Int,
    val level: LogLevel,
    val tag: LogTag,
    val message: String,
    val detail: String? = null
)

/**
 * 当前进程会话标识。每次冷启动 sessionId 自增。
 */
data class SessionMarker(
    val sessionId: Int,
    val startedAtMs: Long
)

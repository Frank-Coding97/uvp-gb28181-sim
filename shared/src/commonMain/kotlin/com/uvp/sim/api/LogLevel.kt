package com.uvp.sim.api

/**
 * 系统日志级别。Debug < Info < Warning < Error。
 *
 * UI 友好公开 API (PR-A T1.3 缩水版搬家). 旧位置 com.uvp.sim.observability.LogLevel
 * 通过 typealias 兜底, shared 内部代码零侵入.
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

package com.uvp.sim.observability

/**
 * 系统日志级别。Debug < Info < Warning < Error。
 *
 * PR-A T1.3: 定义已搬到 [com.uvp.sim.api.LogLevel] 作为 UI 友好公开 API.
 * 这里保留 typealias 兜底, shared 内部 import com.uvp.sim.observability.LogLevel
 * 仍可解析, 无需触发大面积修改.
 *
 * UI level 阈值过滤:选 X 显示 X 及以上(spec Q8)。
 */
typealias LogLevel = com.uvp.sim.api.LogLevel

/**
 * 系统日志分类标签。SIP 故意不在这里 — SIP 事件走 SimEvent,系统日志只记非协议层关注点。
 *
 * PR-A T1.3: 定义已搬到 [com.uvp.sim.api.LogTag] 作为 UI 友好公开 API.
 * 这里保留 typealias 兜底, shared 内部 import com.uvp.sim.observability.LogTag
 * 仍可解析, 无需触发大面积修改.
 */
typealias LogTag = com.uvp.sim.api.LogTag

/**
 * 单条系统日志。
 *
 * - [seq] 单调递增,buffer 排序与暂停期间累积计数靠它(防时钟回拨)
 * - [sessionId] 进每条 log 而不是只在顶部展示,便于多会话拼合后区分
 * - [detail] 可选长文本(stack / 完整报文等),行展开时显示
 * - [category] P3-3 加:Error/Warning 级别 emit 时可带分级,Info/Debug 不强制(默认 null)。
 *   排障时按 category 过滤,补 grep 之外的结构化视图。
 */
data class SystemLog(
    val seq: Long,
    val timestampMs: Long,
    val sessionId: Int,
    val level: LogLevel,
    val tag: LogTag,
    val message: String,
    val detail: String? = null,
    val category: ErrorCategory? = null,
)

/**
 * 当前进程会话标识。每次冷启动 sessionId 自增。
 */
data class SessionMarker(
    val sessionId: Int,
    val startedAtMs: Long
)

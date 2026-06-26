package com.uvp.sim.ui.model

/**
 * UI 层 系统日志 DTO. 1:1 映射 com.uvp.sim.observability.SystemLog.
 * level/tag 持有 com.uvp.sim.observability.LogLevel/LogTag (T1.3 后 typealias = api).
 */
data class SystemLogDto(
    val seq: Long,
    val timestampMs: Long,
    val sessionId: Int,
    val level: com.uvp.sim.observability.LogLevel,
    val tag: com.uvp.sim.observability.LogTag,
    val message: String,
    val detail: String? = null,
)

/** UI 层 SystemEvent 会话开始标记 DTO. 1:1 映射 com.uvp.sim.observability.SessionMarker. */
data class SessionMarkerDto(
    val sessionId: Int,
    val startedAtMs: Long,
)

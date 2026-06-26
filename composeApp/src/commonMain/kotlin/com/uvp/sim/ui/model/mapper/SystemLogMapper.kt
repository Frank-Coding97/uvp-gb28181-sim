package com.uvp.sim.ui.model.mapper

import com.uvp.sim.observability.SessionMarker
import com.uvp.sim.observability.SystemLog
import com.uvp.sim.ui.model.SessionMarkerDto
import com.uvp.sim.ui.model.SystemLogDto

/** PR-A T4.2 实现. level/tag 引用 api.LogLevel/LogTag (B 档,直传). */
fun SystemLog.toDto(): SystemLogDto = SystemLogDto(
    seq = seq,
    timestampMs = timestampMs,
    sessionId = sessionId,
    level = level,
    tag = tag,
    message = message,
    detail = detail,
)

fun SessionMarker.toDto(): SessionMarkerDto = SessionMarkerDto(
    sessionId = sessionId,
    startedAtMs = startedAtMs,
)

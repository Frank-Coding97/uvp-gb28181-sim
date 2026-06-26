package com.uvp.sim.api

import kotlinx.serialization.Serializable

/**
 * 录像类型 — GB28181 RecordType 字段, 决定录像如何标记.
 *
 * UI 友好公开 API (PR-A T1.3 缩水版搬家). 旧位置 com.uvp.sim.recording.RecordType
 * 通过 typealias 兜底, shared 内部代码零侵入.
 */
@Serializable
enum class RecordType(val gb28181Token: String) {
    Time("time"),
    Alarm("alarm"),
    Manual_("manual"),
}

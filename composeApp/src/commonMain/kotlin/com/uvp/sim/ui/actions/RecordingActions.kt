package com.uvp.sim.ui.actions

import com.uvp.sim.recording.RecordingFilter

/**
 * 本地录像动作 — slice 3/4(PR-B)。
 *
 * 范围:本地录像启停 + 删除 + 时间筛选(M3 后续接 GB28181 RecordInfo 查询)。
 *
 * UI 调用点:
 *   - HomeScreen — onRecordingStart / onRecordingStop(主屏录像 tile)
 *   - RecordingScreen — onRecordingDelete + onRecordingFilterApply
 */
interface RecordingActions {
    fun onRecordingStart()
    fun onRecordingStop()
    fun onRecordingDelete(id: String)
    fun onRecordingFilterApply(filter: RecordingFilter)
}

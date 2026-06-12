package com.uvp.sim.recording

/**
 * 录像状态机(plan §3)。
 *
 * 状态:
 *   - Idle:无活跃录像
 *   - Recording(startMs, segmentIndex, source):录像中,segmentIndex 用来防止 30 分钟分段时
 *     onSegmentSaved 误把 Recording 跳回 Idle
 *   - Stopping(previous, reason):录像 finalize 中(MP4 还在写文件尾)
 *   - Failed(reason):权限撤销 / 摄像头被占等
 *
 * 互斥:Recording ⊥ PLAYBACK,在 SimulatorEngine 层把住,这里不掺合。
 */
sealed class RecordingState {
    object Idle : RecordingState()

    data class Recording(
        val startMs: Long,
        val segmentIndex: Int,
        val source: RecordSource
    ) : RecordingState()

    data class Stopping(
        val previous: Recording,
        val reason: String
    ) : RecordingState()

    data class Failed(val reason: String) : RecordingState()
}

/**
 * 状态机事件。
 *
 * - StartRequested:UI 或平台 RecordCmd 下发开始
 * - StopRequested:UI 或平台下发停止
 * - SegmentSplit:30 分钟切片(瞬间切换不进 Stopping)
 * - SegmentFinalized:Stopping 后文件落盘完成,nextEvent 决定回 Idle 还是续录
 * - FailureRaised:Recorder 抛异常 / bindToLifecycle 失败
 * - FailureAcknowledged:UI 上点掉 toast / 老板重启录像隐式 ack
 */
sealed class RecordingEvent {
    data class StartRequested(val source: RecordSource, val nowMs: Long) : RecordingEvent()
    object StopRequested : RecordingEvent()
    object SegmentSplit : RecordingEvent()
    data class SegmentFinalized(val nextEvent: NextAction) : RecordingEvent()
    data class FailureRaised(val reason: String) : RecordingEvent()
    object FailureAcknowledged : RecordingEvent()

    enum class NextAction { ContinueRecording, GoIdle }
}

object RecordingStateMachine {

    /**
     * 纯函数迁移。无效迁移返回原 state(幂等)。
     *
     * 关键迁移:
     *   - Idle ── StartRequested ──> Recording(seg=0)
     *   - Recording ── StopRequested ──> Stopping(reason=user_stop)
     *   - Recording ── SegmentSplit ──> Recording(seg+1)  // 不进 Stopping,瞬间切片
     *   - Stopping ── SegmentFinalized(GoIdle) ──> Idle
     *   - Stopping ── SegmentFinalized(Continue) ──> Recording(seg+1)
     *   - * ── FailureRaised ──> Failed
     *   - Failed ── FailureAcknowledged ──> Idle
     *   - Failed ── StartRequested ──> Recording (隐式 ack)
     *   - Recording ── StartRequested ──> Recording (幂等,B 块需要)
     *   - Idle ── StopRequested ──> Idle (幂等)
     *   - Stopping ── StopRequested ──> Stopping (幂等)
     */
    fun transition(state: RecordingState, event: RecordingEvent): RecordingState {
        // FailureRaised 是全局兜底,任何非 Failed 状态都能进 Failed
        if (event is RecordingEvent.FailureRaised && state !is RecordingState.Failed) {
            return RecordingState.Failed(event.reason)
        }

        return when (state) {
            RecordingState.Idle -> when (event) {
                is RecordingEvent.StartRequested ->
                    RecordingState.Recording(
                        startMs = event.nowMs,
                        segmentIndex = 0,
                        source = event.source
                    )
                else -> state
            }

            is RecordingState.Recording -> when (event) {
                is RecordingEvent.StopRequested ->
                    RecordingState.Stopping(previous = state, reason = "user_stop")
                is RecordingEvent.SegmentSplit ->
                    state.copy(segmentIndex = state.segmentIndex + 1)
                else -> state
            }

            is RecordingState.Stopping -> when (event) {
                is RecordingEvent.SegmentFinalized -> when (event.nextEvent) {
                    RecordingEvent.NextAction.GoIdle -> RecordingState.Idle
                    RecordingEvent.NextAction.ContinueRecording ->
                        state.previous.copy(segmentIndex = state.previous.segmentIndex + 1)
                }
                else -> state
            }

            is RecordingState.Failed -> when (event) {
                RecordingEvent.FailureAcknowledged -> RecordingState.Idle
                is RecordingEvent.StartRequested ->
                    RecordingState.Recording(
                        startMs = event.nowMs,
                        segmentIndex = 0,
                        source = event.source
                    )
                else -> state
            }
        }
    }
}

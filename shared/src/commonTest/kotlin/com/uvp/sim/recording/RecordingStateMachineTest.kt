package com.uvp.sim.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecordingStateMachineTest {

    private fun start(nowMs: Long = 1_000, source: RecordSource = RecordSource.Manual) =
        RecordingEvent.StartRequested(source = source, nowMs = nowMs)

    @Test fun idle_startRequested_goesRecording() {
        val s = RecordingStateMachine.transition(RecordingState.Idle, start(nowMs = 1_000))
        val rec = assertIs<RecordingState.Recording>(s)
        assertEquals(1_000, rec.startMs)
        assertEquals(0, rec.segmentIndex)
        assertEquals(RecordSource.Manual, rec.source)
    }

    @Test fun idle_startRequested_platformCmd_recordsSource() {
        val s = RecordingStateMachine.transition(
            RecordingState.Idle,
            start(source = RecordSource.PlatformCmd)
        )
        assertEquals(RecordSource.PlatformCmd, (s as RecordingState.Recording).source)
    }

    @Test fun recording_stopRequested_goesStopping() {
        val rec = RecordingState.Recording(startMs = 1_000, segmentIndex = 0, source = RecordSource.Manual)
        val s = RecordingStateMachine.transition(rec, RecordingEvent.StopRequested)
        val stopping = assertIs<RecordingState.Stopping>(s)
        assertEquals(rec, stopping.previous)
        assertEquals("user_stop", stopping.reason)
    }

    @Test fun stopping_segmentFinalizedGoIdle_returnsIdle() {
        val rec = RecordingState.Recording(startMs = 1_000, segmentIndex = 0, source = RecordSource.Manual)
        val stopping = RecordingState.Stopping(previous = rec, reason = "user_stop")
        val s = RecordingStateMachine.transition(
            stopping,
            RecordingEvent.SegmentFinalized(RecordingEvent.NextAction.GoIdle)
        )
        assertEquals(RecordingState.Idle, s)
    }

    @Test fun recording_segmentSplit_keepsRecording_segmentIndexBumps() {
        val rec = RecordingState.Recording(startMs = 1_000, segmentIndex = 2, source = RecordSource.Manual)
        val s = RecordingStateMachine.transition(rec, RecordingEvent.SegmentSplit)
        val newRec = assertIs<RecordingState.Recording>(s)
        assertEquals(3, newRec.segmentIndex)
        // 切片不动 startMs(整段时长基准),但 source 保留
        assertEquals(1_000, newRec.startMs)
        assertEquals(RecordSource.Manual, newRec.source)
    }

    @Test fun stopping_segmentFinalizedContinueRecording_resumesRecordingNextSegment() {
        // 30 分钟切片场景:Stopping → SegmentFinalized(Continue) → Recording(seg+1)
        val rec = RecordingState.Recording(startMs = 1_000, segmentIndex = 0, source = RecordSource.Manual)
        val stopping = RecordingState.Stopping(previous = rec, reason = "auto_split")
        val s = RecordingStateMachine.transition(
            stopping,
            RecordingEvent.SegmentFinalized(RecordingEvent.NextAction.ContinueRecording)
        )
        val newRec = assertIs<RecordingState.Recording>(s)
        assertEquals(1, newRec.segmentIndex)
    }

    @Test fun recording_failureRaised_goesFailed() {
        val rec = RecordingState.Recording(startMs = 1_000, segmentIndex = 0, source = RecordSource.Manual)
        val s = RecordingStateMachine.transition(
            rec,
            RecordingEvent.FailureRaised("permission revoked")
        )
        val failed = assertIs<RecordingState.Failed>(s)
        assertEquals("permission revoked", failed.reason)
    }

    @Test fun failed_acknowledged_goesIdle() {
        val s = RecordingStateMachine.transition(
            RecordingState.Failed("io error"),
            RecordingEvent.FailureAcknowledged
        )
        assertEquals(RecordingState.Idle, s)
    }

    @Test fun idle_stopRequested_isNoop() {
        val s = RecordingStateMachine.transition(RecordingState.Idle, RecordingEvent.StopRequested)
        assertEquals(RecordingState.Idle, s)
    }

    @Test fun recording_startRequested_isIdempotent() {
        // B 块:RecordCmd 重复下发,仍处 Recording 不变
        val rec = RecordingState.Recording(startMs = 1_000, segmentIndex = 2, source = RecordSource.PlatformCmd)
        val s = RecordingStateMachine.transition(rec, start(nowMs = 9_999))
        assertEquals(rec, s)
    }

    @Test fun failed_startRequested_goesRecording() {
        // 失败后老板重新点录像,允许从 Failed 直接进 Recording(隐式 ack)
        val s = RecordingStateMachine.transition(
            RecordingState.Failed("io error"),
            start(nowMs = 5_000)
        )
        assertTrue(s is RecordingState.Recording)
    }

    @Test fun stopping_stopRequested_isNoop() {
        // 已经在 Stopping,再点停止不动
        val rec = RecordingState.Recording(startMs = 1_000, segmentIndex = 0, source = RecordSource.Manual)
        val stopping = RecordingState.Stopping(previous = rec, reason = "user_stop")
        val s = RecordingStateMachine.transition(stopping, RecordingEvent.StopRequested)
        assertEquals(stopping, s)
    }
}

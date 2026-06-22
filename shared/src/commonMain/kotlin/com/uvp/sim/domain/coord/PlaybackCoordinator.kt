package com.uvp.sim.domain.coord

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 回放(PLAYBACK / DOWNLOAD)对话域。
 *
 * 接管的 SIP 流程(plan 第 2.3 节):
 * - INVITE 含 SDP s=Playback / s=Download / s=Download(M2 录像回放)
 * - INFO + MANSRTSP body(seek / scale / teardown)
 * - 对应 dialog 的 BYE
 * - ActivePlayback 状态(seek / 倍速 / 进度)
 *
 * 来自 SimulatorEngine 的方法迁移清单:handlePlaybackInvite / handleInfo(MANSRTSP) /
 * stopActivePlayback + 内部 ActivePlayback data class
 */
internal interface PlaybackCoordinator : Coordinator {
    val state: StateFlow<PlaybackState>
    val events: SharedFlow<PlaybackEvent>

    /** 用户主动停止 / 错误恢复。 */
    suspend fun stop(reason: String = "user stop")
}

internal enum class PlaybackState {
    Idle,
    Inviting,
    Playing,
    Paused,
    Stopping,
}

internal sealed class PlaybackEvent {
    data class Started(val callId: String, val channelId: String, val startTimeMs: Long) : PlaybackEvent()
    data class SeekedTo(val positionMs: Long) : PlaybackEvent()
    data class ScaleChanged(val scale: Float) : PlaybackEvent()
    data class Stopped(val callId: String, val reason: String) : PlaybackEvent()
    data class Error(val reason: String) : PlaybackEvent()
}

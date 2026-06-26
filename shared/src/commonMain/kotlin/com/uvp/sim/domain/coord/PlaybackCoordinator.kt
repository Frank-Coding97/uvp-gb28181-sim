package com.uvp.sim.domain.coord

import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 回放(PLAYBACK / DOWNLOAD)对话域。
 *
 * PR5 完整抽离:接管 InviteCoordinatorImpl 上的回放路径(handlePlaybackInvite /
 * handleInfo MANSRTSP / sendBye / sendMediaStatusNotify / stopActivePlayback /
 * ActivePlayback / MediaMode)。
 *
 * onIncoming 路由:
 * - INVITE + SDP s=Playback / s=Download → handlePlaybackInvite,Handled
 * - INVITE 其他 → Skip(给 Invite 接)
 * - INFO + 有 activePlayback → handleInfo(MANSRTSP),Handled
 * - INFO 其他 → Skip
 * - BYE + callId 命中 activePlayback → stopActivePlayback + 200 OK,Handled
 * - 其他 → Skip
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

internal enum class PlaybackMediaMode { PLAYBACK, DOWNLOAD }

internal sealed class PlaybackEvent {
    data class Started(val callId: String, val ssrc: String, val isDownload: Boolean) : PlaybackEvent()
    data class SeekedTo(val positionMs: Long) : PlaybackEvent()
    data class ScaleChanged(val scale: Double) : PlaybackEvent()
    data class Stopped(val callId: String, val reason: String) : PlaybackEvent()
    data class TransportError(val message: String) : PlaybackEvent()
    data class MessageSent(val message: SipMessage) : PlaybackEvent()
}

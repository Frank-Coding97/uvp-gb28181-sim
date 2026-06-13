package com.uvp.sim.domain

import com.uvp.sim.sip.SipMessage
import kotlinx.datetime.Clock

/**
 * High-level events emitted by [SimulatorEngine] for the UI to render.
 * Distinct from [com.uvp.sim.sip.SipEvent] (low-level state-machine events).
 *
 * 每条事件携带 [timestampMs] — emit 时即拍快照,UI 时序图 / 列表渲染时直接用。
 */
sealed class SimEvent {
    abstract val timestampMs: Long

    private companion object {
        fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
    }

    data class RegistrationStarted(
        val server: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class RegistrationChallenged(
        val nonce: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class RegistrationSucceeded(
        val expiresSeconds: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class RegistrationFailed(
        val reason: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class HeartbeatSent(
        val sequence: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class HeartbeatAcknowledged(
        val sequence: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class IncomingInvite(
        val callId: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class CallEnded(
        val callId: String,
        val reason: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    /**
     * Sent right after we 200-OK an INVITE, before any RTP goes out.
     * Carries the negotiated remote endpoint + SSRC for diagnostics.
     */
    data class StreamStarted(
        val callId: String,
        val remoteHost: String,
        val remotePort: Int,
        val ssrc: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    /** Sent on local stop, BYE, or transport error during streaming. */
    data class StreamStopped(
        val callId: String,
        val frameCount: Int,
        val packetCount: Int,
        val reason: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    /** 周期 stats — UI 时序图用最新一条挂到活跃 MediaSegment(spec §6.2 RTP 推送中)。 */
    data class StreamStats(
        val callId: String,
        val frameCount: Int,
        val packetCount: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    /** T15 — A snapshot Alarm Notify was emitted to the platform. */
    data class SnapshotReported(
        val sn: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    /** Raw SIP envelope for the log view. */
    data class MessageSent(
        val message: SipMessage,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class MessageReceived(
        val message: SipMessage,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class TransportError(
        val description: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class HeartbeatTimeoutDetected(
        val missedCount: Int,
        val maxAllowed: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class AutoReregisterTriggered(
        val reason: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class RegistrationRetryScheduled(
        val delayMs: Long,
        val attempt: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class InviteAckTimeout(
        val callId: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    /** 平台下发 DeviceControl 子命令 — UI 日志列表用. */
    data class DeviceControlReceived(
        val commandType: String,
        val detail: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()

    data class SubscribeReceived(
        val subscriber: String,
        val kind: String,
        val expiresSeconds: Int,
        val intervalSeconds: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class NotifySent(
        val kind: String,
        val sn: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class SubscribeExpired(
        val subscriber: String,
        val kind: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
    data class SubscribeRefreshed(
        val subscriber: String,
        val newExpiresSeconds: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()
}

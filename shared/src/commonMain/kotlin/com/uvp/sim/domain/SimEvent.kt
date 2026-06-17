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

    /**
     * T10 (7.5 GB-2022) — 平台 SnapShotConfig 触发的抓拍 + 上传成功后,设备发出 SnapShot
     * Notify 的事件。一次 SnapShotConfig 序列每张图触发一次本事件,最后一张时 [count] == [total]。
     */
    data class SnapshotUploaded(
        val sessionId: String,
        val snapShotId: String,
        val count: Int,
        val total: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()

    /**
     * T10 (7.5) — 抓拍序列内某张图终态失败(retry 3 次仍失败,跳过 NOTIFY)。
     */
    data class SnapshotUploadFailed(
        val sessionId: String,
        val snapShotId: String,
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

    // ---------- M2 Alarm (主动 + 反向 + 订阅) ----------

    /** 报警复位来源:本地用户操作 / 平台 AlarmCmd 远程下发。 */
    sealed class ResetSource {
        data object Local : ResetSource()
        data class Remote(val subscriber: String) : ResetSource()
    }

    /** 设备主动发起报警(reportAlarm)。 */
    data class AlarmFired(
        val type: com.uvp.sim.gb28181.AlarmType,
        val priority: com.uvp.sim.gb28181.AlarmPriority,
        val description: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()

    /** 报警复位(本地确认 或 平台 AlarmCmd)。 */
    data class AlarmReset(
        val by: ResetSource,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()

    /** 平台 SUBSCRIBE Event:Alarm 成功建立订阅。 */
    data class AlarmSubscribed(
        val subscriber: String,
        val expires: Int,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()

    /** 每条报警 NOTIFY 推给某个订阅人。 */
    data class AlarmNotifySent(
        val sn: String,
        val subscriber: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()

    /** Alarm 订阅取消 / 自然过期。 */
    data class AlarmSubscriptionExpired(
        val subscriber: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()

    /**
     * 网络变化触发的事件。
     *
     * [NetworkBound] / [NetworkAuto]: 触发后 Engine 会做 unregister → register 序列
     *                  (handleNetworkChange 编排)
     * [NetworkUnavailable]: 网卡不可用,Engine 不主动 unregister(发不出去),UI 显示 banner
     */
    data class NetworkBound(
        val preference: String,
        val interfaceName: String,
        val localIp: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()

    data class NetworkUnavailable(
        val reason: String,
        override val timestampMs: Long = nowMs()
    ) : SimEvent()

    object NetworkAuto : SimEvent() {
        override val timestampMs: Long = nowMs()
    }
}

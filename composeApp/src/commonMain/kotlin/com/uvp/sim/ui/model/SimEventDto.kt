package com.uvp.sim.ui.model

/**
 * UI 层 SIM 事件 DTO. T-A3 完成后, UI 不再 import com.uvp.sim.domain.SimEvent.
 * sealed 形状保留, 编译期保证 UI when-exhaustive.
 *
 * 子类型按域归类: Registration / Heartbeat / Call+Stream / Snapshot / SIP message /
 * Device control / Subscription / Alarm / Broadcast / Network.
 */
sealed class SimEventDto {
    abstract val timestampMs: Long

    // Registration
    data class RegistrationStarted(val server: String, override val timestampMs: Long) : SimEventDto()
    data class RegistrationChallenged(val nonce: String, override val timestampMs: Long) : SimEventDto()
    data class RegistrationSucceeded(val expiresSeconds: Int, override val timestampMs: Long) : SimEventDto()
    data class RegistrationFailed(val reason: String, override val timestampMs: Long) : SimEventDto()
    data class RegistrationRetryScheduled(val delayMs: Long, val attempt: Int, override val timestampMs: Long) : SimEventDto()
    data class AutoReregisterTriggered(val reason: String, override val timestampMs: Long) : SimEventDto()

    // Heartbeat
    data class HeartbeatSent(val sequence: Int, override val timestampMs: Long) : SimEventDto()
    data class HeartbeatAcknowledged(val sequence: Int, override val timestampMs: Long) : SimEventDto()
    data class HeartbeatTimeoutDetected(val missedCount: Int, val maxAllowed: Int, override val timestampMs: Long) : SimEventDto()

    // Call + Stream
    data class IncomingInvite(val callId: String, override val timestampMs: Long) : SimEventDto()
    data class CallEnded(val callId: String, val reason: String, override val timestampMs: Long) : SimEventDto()
    data class StreamStarted(
        val callId: String,
        val remoteHost: String,
        val remotePort: Int,
        val ssrc: String,
        override val timestampMs: Long,
    ) : SimEventDto()
    data class StreamStopped(
        val callId: String,
        val frameCount: Int,
        val packetCount: Int,
        val reason: String,
        override val timestampMs: Long,
    ) : SimEventDto()
    data class StreamStats(
        val callId: String,
        val frameCount: Int,
        val packetCount: Int,
        override val timestampMs: Long,
    ) : SimEventDto()
    data class InviteAckTimeout(val callId: String, override val timestampMs: Long) : SimEventDto()

    // Snapshot + MediaStatus
    data class SnapshotReported(val sn: String, override val timestampMs: Long) : SimEventDto()
    data class MediaStatusSent(val notifyType: Int, val subscriberCount: Int, override val timestampMs: Long) : SimEventDto()
    data class SnapshotUploaded(
        val sessionId: String,
        val snapShotId: String,
        val count: Int,
        val total: Int,
        override val timestampMs: Long,
    ) : SimEventDto()
    data class SnapshotUploadFailed(
        val sessionId: String,
        val snapShotId: String,
        override val timestampMs: Long,
    ) : SimEventDto()

    // SIP message (嵌套 DTO)
    data class MessageSent(val message: SipMessageDto, override val timestampMs: Long) : SimEventDto()
    data class MessageReceived(val message: SipMessageDto, override val timestampMs: Long) : SimEventDto()
    data class TransportError(val description: String, override val timestampMs: Long) : SimEventDto()

    // Device control
    data class DeviceControlReceived(val commandType: String, val detail: String, override val timestampMs: Long) : SimEventDto()

    // Subscription
    data class SubscribeReceived(
        val subscriber: String,
        val kind: String,
        val expiresSeconds: Int,
        val intervalSeconds: Int,
        override val timestampMs: Long,
    ) : SimEventDto()
    data class NotifySent(val kind: String, val sn: Int, override val timestampMs: Long) : SimEventDto()
    data class SubscribeExpired(val subscriber: String, val kind: String, override val timestampMs: Long) : SimEventDto()
    data class SubscribeRefreshed(val subscriber: String, val newExpiresSeconds: Int, override val timestampMs: Long) : SimEventDto()

    // Alarm
    data class AlarmFired(
        val type: AlarmTypeDto,
        val priority: AlarmPriorityDto,
        val description: String,
        override val timestampMs: Long,
    ) : SimEventDto()
    data class AlarmReset(val by: ResetSourceDto, override val timestampMs: Long) : SimEventDto()
    data class AlarmSubscribed(val subscriber: String, val expires: Int, override val timestampMs: Long) : SimEventDto()
    data class AlarmNotifySent(val sn: String, val subscriber: String, override val timestampMs: Long) : SimEventDto()
    data class AlarmSubscriptionExpired(val subscriber: String, override val timestampMs: Long) : SimEventDto()

    // Broadcast
    data class BroadcastReceived(val sourceId: String, val targetId: String, override val timestampMs: Long) : SimEventDto()
    data class BroadcastInvited(val platformUri: String, val localPort: Int, override val timestampMs: Long) : SimEventDto()
    data class BroadcastStarted(val firstPacketDelayMs: Long, override val timestampMs: Long) : SimEventDto()
    data class BroadcastPacketRx(
        val rxPackets: Long,
        val rxBytes: Long,
        val codec: String,
        override val timestampMs: Long,
    ) : SimEventDto()
    data class BroadcastEnded(
        val reason: BroadcastEndReasonDto,
        val durationMs: Long,
        override val timestampMs: Long,
    ) : SimEventDto()

    // Network
    data class NetworkBound(
        val preference: String,
        val interfaceName: String,
        val localIp: String,
        override val timestampMs: Long,
    ) : SimEventDto()
    data class NetworkUnavailable(val reason: String, override val timestampMs: Long) : SimEventDto()
    data object NetworkAuto : SimEventDto() {
        override val timestampMs: Long = 0L
    }
}

/** Alarm reset 来源, 镜像 domain.SimEvent.ResetSource. */
sealed class ResetSourceDto {
    data object Local : ResetSourceDto()
    data class Remote(val subscriber: String) : ResetSourceDto()
}

/** Broadcast 结束原因, 镜像 domain.BroadcastEndReason. */
enum class BroadcastEndReasonDto { Local, Remote, Error, InviteFailed, CodecRejected }

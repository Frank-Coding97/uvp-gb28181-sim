package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.ui.model.BroadcastEndReasonDto
import com.uvp.sim.ui.model.ResetSourceDto
import com.uvp.sim.ui.model.SimEventDto

/**
 * PR-A T3.2 实现. sealed when-exhaustive 38 variant + ResetSource + BroadcastEndReason.
 */
fun SimEvent.toDto(): SimEventDto = when (this) {
    // Registration
    is SimEvent.RegistrationStarted -> SimEventDto.RegistrationStarted(server, timestampMs)
    is SimEvent.RegistrationChallenged -> SimEventDto.RegistrationChallenged(nonce, timestampMs)
    is SimEvent.RegistrationSucceeded -> SimEventDto.RegistrationSucceeded(expiresSeconds, timestampMs)
    is SimEvent.RegistrationFailed -> SimEventDto.RegistrationFailed(reason, timestampMs)
    is SimEvent.RegistrationRetryScheduled -> SimEventDto.RegistrationRetryScheduled(delayMs, attempt, timestampMs)
    is SimEvent.AutoReregisterTriggered -> SimEventDto.AutoReregisterTriggered(reason, timestampMs)

    // Heartbeat
    is SimEvent.HeartbeatSent -> SimEventDto.HeartbeatSent(sequence, timestampMs)
    is SimEvent.HeartbeatAcknowledged -> SimEventDto.HeartbeatAcknowledged(sequence, timestampMs)
    is SimEvent.HeartbeatTimeoutDetected -> SimEventDto.HeartbeatTimeoutDetected(missedCount, maxAllowed, timestampMs)

    // Call + Stream
    is SimEvent.IncomingInvite -> SimEventDto.IncomingInvite(callId, timestampMs)
    is SimEvent.CallEnded -> SimEventDto.CallEnded(callId, reason, timestampMs)
    is SimEvent.StreamStarted -> SimEventDto.StreamStarted(callId, remoteHost, remotePort, ssrc, timestampMs)
    is SimEvent.StreamStopped -> SimEventDto.StreamStopped(callId, frameCount, packetCount, reason, timestampMs)
    is SimEvent.StreamStats -> SimEventDto.StreamStats(callId, frameCount, packetCount, timestampMs)
    is SimEvent.InviteAckTimeout -> SimEventDto.InviteAckTimeout(callId, timestampMs)

    // Snapshot + MediaStatus
    is SimEvent.SnapshotReported -> SimEventDto.SnapshotReported(sn, timestampMs)
    is SimEvent.MediaStatusSent -> SimEventDto.MediaStatusSent(notifyType, subscriberCount, timestampMs)
    is SimEvent.SnapshotUploaded -> SimEventDto.SnapshotUploaded(sessionId, snapShotId, count, total, timestampMs)
    is SimEvent.SnapshotUploadFailed -> SimEventDto.SnapshotUploadFailed(sessionId, snapShotId, timestampMs)

    // SIP message (嵌套 DTO)
    is SimEvent.MessageSent -> SimEventDto.MessageSent(message.toDto(), timestampMs)
    is SimEvent.MessageReceived -> SimEventDto.MessageReceived(message.toDto(), timestampMs)
    is SimEvent.TransportError -> SimEventDto.TransportError(description, timestampMs)

    // Device control
    is SimEvent.DeviceControlReceived -> SimEventDto.DeviceControlReceived(commandType, detail, timestampMs)

    // Subscription
    is SimEvent.SubscribeReceived -> SimEventDto.SubscribeReceived(subscriber, kind, expiresSeconds, intervalSeconds, timestampMs)
    is SimEvent.NotifySent -> SimEventDto.NotifySent(kind, sn, timestampMs)
    is SimEvent.SubscribeExpired -> SimEventDto.SubscribeExpired(subscriber, kind, timestampMs)
    is SimEvent.SubscribeRefreshed -> SimEventDto.SubscribeRefreshed(subscriber, newExpiresSeconds, timestampMs)

    // Alarm
    is SimEvent.AlarmFired -> SimEventDto.AlarmFired(type.toDto(), priority.toDto(), description, timestampMs)
    is SimEvent.AlarmReset -> SimEventDto.AlarmReset(by.toDto(), timestampMs)
    is SimEvent.AlarmSubscribed -> SimEventDto.AlarmSubscribed(subscriber, expires, timestampMs)
    is SimEvent.AlarmNotifySent -> SimEventDto.AlarmNotifySent(sn, subscriber, timestampMs)
    is SimEvent.AlarmSubscriptionExpired -> SimEventDto.AlarmSubscriptionExpired(subscriber, timestampMs)

    // Broadcast
    is SimEvent.BroadcastReceived -> SimEventDto.BroadcastReceived(sourceId, targetId, timestampMs)
    is SimEvent.BroadcastInvited -> SimEventDto.BroadcastInvited(platformUri, localPort, timestampMs)
    is SimEvent.BroadcastStarted -> SimEventDto.BroadcastStarted(firstPacketDelayMs, timestampMs)
    is SimEvent.BroadcastPacketRx -> SimEventDto.BroadcastPacketRx(rxPackets, rxBytes, codec, timestampMs)
    is SimEvent.BroadcastEnded -> SimEventDto.BroadcastEnded(reason.toDto(), durationMs, timestampMs)

    // Network
    is SimEvent.NetworkBound -> SimEventDto.NetworkBound(preference, interfaceName, localIp, timestampMs)
    is SimEvent.NetworkUnavailable -> SimEventDto.NetworkUnavailable(reason, timestampMs)
    SimEvent.NetworkAuto -> SimEventDto.NetworkAuto
}

fun SimEvent.ResetSource.toDto(): ResetSourceDto = when (this) {
    SimEvent.ResetSource.Local -> ResetSourceDto.Local
    is SimEvent.ResetSource.Remote -> ResetSourceDto.Remote(subscriber)
}

fun BroadcastEndReason.toDto(): BroadcastEndReasonDto = BroadcastEndReasonDto.valueOf(name)

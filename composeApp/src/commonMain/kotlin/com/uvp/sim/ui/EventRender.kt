package com.uvp.sim.ui

import com.uvp.sim.domain.SimEvent
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse

/**
 * Shared event renderer used by both the mini event list on Home and the
 * full Log screen. Returns (label, detail) so callers can format flexibly.
 */
fun renderSimEvent(e: SimEvent): Pair<String, String> = when (e) {
    is SimEvent.RegistrationStarted -> "REGISTER →" to e.server
    is SimEvent.RegistrationChallenged -> "401 challenge" to e.nonce.take(80)
    is SimEvent.RegistrationSucceeded -> "✓ Registered" to "expires=${e.expiresSeconds}s"
    is SimEvent.RegistrationFailed -> "✗ Register failed" to e.reason
    is SimEvent.HeartbeatSent -> "♥ Keepalive #${e.sequence}" to ""
    is SimEvent.HeartbeatAcknowledged -> "♥ ack #${e.sequence}" to ""
    is SimEvent.IncomingInvite -> "INVITE ←" to e.callId
    is SimEvent.StreamStarted -> "▶ Streaming" to "${e.remoteHost}:${e.remotePort} ssrc=${e.ssrc}"
    is SimEvent.StreamStopped -> "■ Stopped" to "${e.frameCount}f / ${e.packetCount}p · ${e.reason}"
    is SimEvent.CallEnded -> "Call ended" to "${e.callId} (${e.reason})"
    is SimEvent.SnapshotReported -> "📸 Snapshot reported" to "SN=${e.sn}"
    is SimEvent.MessageSent -> "TX ${describeMsg(e.message)}" to ""
    is SimEvent.MessageReceived -> "RX ${describeMsg(e.message)}" to ""
    is SimEvent.TransportError -> "⚠ Transport error" to e.description
}

private fun describeMsg(m: SipMessage): String = when (m) {
    is SipRequest -> m.method.name
    is SipResponse -> "${m.statusCode} ${m.reasonPhrase}"
}

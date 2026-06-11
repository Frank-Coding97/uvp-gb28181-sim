package com.uvp.sim.domain

import com.uvp.sim.sip.SipMessage

/**
 * High-level events emitted by [SimulatorEngine] for the UI to render.
 * Distinct from [com.uvp.sim.sip.SipEvent] (low-level state-machine events).
 */
sealed class SimEvent {
    data class RegistrationStarted(val server: String) : SimEvent()
    data class RegistrationChallenged(val nonce: String) : SimEvent()
    data class RegistrationSucceeded(val expiresSeconds: Int) : SimEvent()
    data class RegistrationFailed(val reason: String) : SimEvent()
    data class HeartbeatSent(val sequence: Int) : SimEvent()
    data class HeartbeatAcknowledged(val sequence: Int) : SimEvent()
    data class IncomingInvite(val callId: String) : SimEvent()
    data class CallEnded(val callId: String, val reason: String) : SimEvent()
    /**
     * Sent right after we 200-OK an INVITE, before any RTP goes out.
     * Carries the negotiated remote endpoint + SSRC for diagnostics.
     */
    data class StreamStarted(
        val callId: String,
        val remoteHost: String,
        val remotePort: Int,
        val ssrc: String
    ) : SimEvent()
    /** Sent on local stop, BYE, or transport error during streaming. */
    data class StreamStopped(
        val callId: String,
        val frameCount: Int,
        val packetCount: Int,
        val reason: String
    ) : SimEvent()
    /** Raw SIP envelope for the log view. */
    data class MessageSent(val message: SipMessage) : SimEvent()
    data class MessageReceived(val message: SipMessage) : SimEvent()
    data class TransportError(val description: String) : SimEvent()
}

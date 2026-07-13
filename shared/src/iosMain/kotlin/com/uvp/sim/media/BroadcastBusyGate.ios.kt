package com.uvp.sim.media

/**
 * iOS capture and broadcast playback now share one AVAudioSession coordinator, so an active
 * microphone is no longer a reason to reject a Broadcast request.
 */
actual object BroadcastBusyGate {
    actual fun isBusy(): Boolean = false

    actual fun busyReason(): String? = null
}

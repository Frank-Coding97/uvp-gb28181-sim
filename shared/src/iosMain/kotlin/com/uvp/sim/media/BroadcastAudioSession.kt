package com.uvp.sim.media

import com.uvp.sim.camera.IosAudioSessionCoordinator
import com.uvp.sim.camera.IosAudioSessionLease
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import platform.Foundation.NSLock

/**
 * Broadcast playback's lease on the process-wide iOS audio session.
 *
 * Broadcast and uplink capture share one AVAudioSession. The coordinator keeps it in
 * PlayAndRecord while either owner is active, so starting playback never reconfigures or
 * deactivates the microphone's audio route.
 */
object BroadcastAudioSession {
    private val lock = NSLock()
    private var lease: IosAudioSessionLease? = null

    val isActive: Boolean
        get() {
            lock.lock()
            return try {
                lease != null
            } finally {
                lock.unlock()
            }
        }

    fun activate(sampleRate: Int, channels: Int): Boolean {
        lock.lock()
        try {
            if (lease != null) {
                SystemLogger.emit(
                    LogLevel.Info,
                    LogTag.Media,
                    "BROADCAST_AUDIO_SESSION_ACTIVATE_IDEMPOTENT sr=$sampleRate ch=$channels",
                )
                return true
            }
            val acquiredLease = IosAudioSessionCoordinator.acquire()
            if (acquiredLease == null) {
                SystemLogger.emit(
                    LogLevel.Error,
                    LogTag.Media,
                    "BROADCAST_AUDIO_SESSION_UNAVAILABLE sr=$sampleRate ch=$channels",
                )
                return false
            }
            lease = acquiredLease
            SystemLogger.emit(
                LogLevel.Info,
                LogTag.Media,
                "BROADCAST_AUDIO_SESSION_ACTIVE sr=$sampleRate ch=$channels",
            )
            return true
        } finally {
            lock.unlock()
        }
    }

    fun deactivate() {
        lock.lock()
        val heldLease = try {
            lease.also { lease = null }
        } finally {
            lock.unlock()
        } ?: return

        heldLease.release()
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "BROADCAST_AUDIO_SESSION_RELEASED")
    }

    /** App entering background stops broadcast playback; uplink may continue holding the session. */
    fun onEnterBackground() {
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "BROADCAST_AUDIO_SESSION_ENTER_BG")
        deactivate()
    }

    fun onEnterForeground() {
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "BROADCAST_AUDIO_SESSION_ENTER_FG")
    }

    internal fun resetForTest() {
        deactivate()
    }
}

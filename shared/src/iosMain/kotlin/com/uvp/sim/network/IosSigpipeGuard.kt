package com.uvp.sim.network

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import platform.Foundation.NSLock
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.signal

/** Keeps process-wide signal installation idempotent across resource rebuilds. */
internal class SigpipeIgnoreGate {
    private var installed = false

    fun install(installIgnoreHandler: () -> Unit): Boolean {
        if (installed) return false
        installIgnoreHandler()
        installed = true
        return true
    }
}

/**
 * Darwin terminates a process by default when a TCP peer closes and a later write raises SIGPIPE.
 * Ignore the signal once so Ktor can surface EPIPE to the sending coroutine for normal cleanup.
 */
internal object IosSigpipeGuard {
    private val lock = NSLock()
    private val gate = SigpipeIgnoreGate()

    fun install() {
        lock.lock()
        try {
            if (gate.install { signal(SIGPIPE, SIG_IGN) }) {
                SystemLogger.emit(LogLevel.Info, LogTag.Network, "IOS_SIGPIPE_IGNORED")
            }
        } finally {
            lock.unlock()
        }
    }
}

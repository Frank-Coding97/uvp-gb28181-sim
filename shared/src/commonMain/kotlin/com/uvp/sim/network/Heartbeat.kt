package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic heartbeat scheduler.
 *
 * Used by SimulatorEngine to send GB28181 Keepalive MESSAGE every 60 s
 * after a successful registration. The first tick fires AFTER the first
 * delay, not immediately — the engine should send an initial keepalive on
 * registration success, then start the heartbeat.
 */
class Heartbeat(
    private val intervalMillis: Long,
    private val scope: CoroutineScope,
    private val onTick: suspend () -> Unit
) {
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                if (!isActive) break
                onTick()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

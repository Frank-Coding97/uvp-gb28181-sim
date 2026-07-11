package com.uvp.sim.camera

/**
 * Runs an AVCaptureSession configuration transaction and only starts the
 * session after the transaction has been committed.
 */
internal fun configureThenStartSession(
    beginConfiguration: () -> Unit,
    configure: () -> Boolean,
    commitConfiguration: () -> Unit,
    startRunning: () -> Unit,
): Boolean {
    beginConfiguration()
    val configured = try {
        configure()
    } finally {
        commitConfiguration()
    }
    if (!configured) return false

    startRunning()
    return true
}

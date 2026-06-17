package com.uvp.sim.snapshot

actual class SnapshotCapture actual constructor() {
    actual suspend fun takeJpeg(): ByteArray? = null
}

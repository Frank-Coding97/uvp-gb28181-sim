package com.uvp.sim.snapshot

actual class SnapshotCapture actual constructor() {
    actual suspend fun takeJpeg(): ByteArray? {
        // iOS stub — 跟 voice-broadcast 节奏一致,留 M5+
        return null
    }
}

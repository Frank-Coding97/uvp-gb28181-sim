package com.uvp.sim.snapshot

actual class JpegLocalCache actual constructor() {
    actual suspend fun write(snapShotId: String, bytes: ByteArray): String {
        // iOS stub — 跟 SnapshotCapture iOS stub 节奏一致,M5+ 上 NSFileManager 实现
        return ""
    }

    actual suspend fun gc() {
        // no-op
    }
}

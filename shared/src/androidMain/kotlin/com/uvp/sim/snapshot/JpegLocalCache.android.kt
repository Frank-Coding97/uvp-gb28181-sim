package com.uvp.sim.snapshot

/**
 * Android actual 占位 stub。T6 接入 Context.filesDir 真实落盘 + GC。
 *
 * 当前阶段(T4)只保 KMP 编译通过 + 同 JVM 行为契约。
 */
actual class JpegLocalCache actual constructor() {
    actual suspend fun write(snapShotId: String, bytes: ByteArray): String = ""
    actual suspend fun gc() {}
}

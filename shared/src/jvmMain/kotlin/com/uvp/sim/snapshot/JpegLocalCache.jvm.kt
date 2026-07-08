package com.uvp.sim.snapshot

import java.io.File
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * JVM 实现 — 同时作为 commonTest 真实写盘 fake。
 *
 * 默认存储到 `<java.io.tmpdir>/uvp-snapshots/`,测试通过 [forRoot] 工厂指定隔离目录。
 */
actual class JpegLocalCache actual constructor() {

    private var rootOverride: File? = null

    private val root: File
        get() = rootOverride ?: File(System.getProperty("java.io.tmpdir"), "uvp-snapshots")

    actual suspend fun write(snapShotId: String, bytes: ByteArray): String {
        val today = todayFolder()
        val dir = File(root, today).apply { mkdirs() }
        val file = File(dir, "$snapShotId.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    actual suspend fun gc() {
        if (!root.exists()) return
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val maxBytes = 100L * 1024 * 1024

        val all = root.walkTopDown().filter { it.isFile }.toList()

        // 1) 删 7 天前
        val survivors = all.filter { f ->
            if (now - f.lastModified() > sevenDaysMs) {
                f.delete()
                false
            } else true
        }

        // 2) 超 100MB 按 mtime 由旧到新淘汰
        val totalNow = survivors.sumOf { it.length() }
        if (totalNow <= maxBytes) return
        var remaining = totalNow
        survivors.sortedBy { it.lastModified() }.forEach { f ->
            if (remaining <= maxBytes) return@forEach
            remaining -= f.length()
            f.delete()
        }
    }

    private fun todayFolder(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return "%04d-%02d-%02d".format(now.year, now.monthNumber, now.dayOfMonth)
    }

    companion object {
        /** 测试用工厂:指定根目录,实例化后隔离环境 */
        fun forRoot(root: File): JpegLocalCache = JpegLocalCache().apply {
            this.rootOverride = root
        }
    }
}

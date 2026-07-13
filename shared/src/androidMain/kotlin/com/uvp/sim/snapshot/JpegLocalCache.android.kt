package com.uvp.sim.snapshot

import android.content.Context
import java.io.File
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Android 真实落盘:`<filesDir>/snapshots/<YYYY-MM-DD>/<id>.jpg`
 *
 * 通过 [forContext] 工厂构造 — KMP expect class 的无参 ctor 走 stub 路径(直接构造无效),
 * 必须经 forContext 注入应用级 Context 才能落盘。
 */
actual class JpegLocalCache actual constructor() {

    private var rootDir: File? = null

    actual suspend fun write(snapShotId: String, bytes: ByteArray): String {
        val root = rootDir ?: return ""
        val today = todayFolder()
        val dir = File(root, today).apply { mkdirs() }
        val file = File(dir, "$snapShotId.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    actual suspend fun gc() {
        val root = rootDir ?: return
        if (!root.exists()) return
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val maxBytes = 100L * 1024 * 1024

        val all = root.walkTopDown().filter { it.isFile }.toList()

        val survivors = all.filter { f ->
            if (now - f.lastModified() > sevenDaysMs) {
                f.delete()
                false
            } else true
        }

        val totalNow = survivors.sumOf { it.length() }
        if (totalNow <= maxBytes) return
        // R3 round-2 #4 (MEDIUM/correctness):原写法 `remaining -= f.length(); f.delete()` 在 delete 失败时
        // 也减 quota,下次 GC 看到缓存"小于 100MB"但磁盘其实在涨。改成成功删除才减。
        var remaining = totalNow
        survivors.sortedBy { it.lastModified() }.forEach { f ->
            if (remaining <= maxBytes) return@forEach
            val len = f.length()
            if (f.delete()) {
                remaining -= len
            }
        }
    }

    private fun todayFolder(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val y = now.year.toString().padStart(4, '0')
        val m = now.monthNumber.toString().padStart(2, '0')
        val d = now.dayOfMonth.toString().padStart(2, '0')
        return "$y-$m-$d"
    }

    companion object {
        /** Android 平台壳启动时调,把 Context.filesDir 锚点写入 cache 根目录。 */
        fun forContext(context: Context): JpegLocalCache = JpegLocalCache().apply {
            rootDir = File(context.filesDir, "snapshots")
        }
    }
}

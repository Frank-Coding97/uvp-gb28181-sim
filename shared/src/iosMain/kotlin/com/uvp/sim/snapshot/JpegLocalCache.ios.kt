package com.uvp.sim.snapshot

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * iOS 落盘实现:`<NSCachesDirectory>/uvp-snapshots/<YYYY-MM-DD>/<id>.jpg`
 *
 * 对齐 Android/JVM 存储策略:
 *   - 按日期分子目录
 *   - GC:删 7 天前 + 总量 > 100MB 从最旧淘汰(删失败不减 quota)
 *   - `NSCachesDirectory` 系统可清,符合 "cache" 语义(iOS 存储紧张时会自动回收)
 *
 * 相较 Android 版无 `forContext` 注入 —— iOS 沙盒 caches 走进程级 API,
 * `NSSearchPathForDirectoriesInDomains` 直接就地取回,不需要 Context 传递。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class JpegLocalCache actual constructor() {

    private val fileManager: NSFileManager get() = NSFileManager.defaultManager

    /** 沙盒 caches 根:`.../Library/Caches/uvp-snapshots` */
    private val root: String by lazy { resolveRoot() }

    private fun resolveRoot(): String {
        val caches = NSSearchPathForDirectoriesInDomains(
            directory = NSCachesDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        )
        val base = caches.firstOrNull() as? String ?: run {
            SystemLogger.emit(
                level = LogLevel.Error,
                tag = LogTag.Resource,
                message = "JpegLocalCache.ios: NSCachesDirectory lookup empty — fallback NSTemporaryDirectory",
            )
            NSTemporaryDirectory()
        }
        return (base as NSString).stringByAppendingPathComponent("uvp-snapshots")
    }

    actual suspend fun write(snapShotId: String, bytes: ByteArray): String {
        val today = todayFolder()
        val dir = (root as NSString).stringByAppendingPathComponent(today)
        ensureDir(dir)
        val filename = "$snapShotId.jpg"
        val filePath = (dir as NSString).stringByAppendingPathComponent(filename)

        val data = bytes.toNSData()
        val ok = data.writeToFile(filePath, atomically = true)
        if (!ok) {
            SystemLogger.emit(
                level = LogLevel.Error,
                tag = LogTag.Resource,
                message = "JpegLocalCache.ios: writeToFile failed for $filePath (${bytes.size} bytes)",
            )
            return ""
        }
        return filePath
    }

    actual suspend fun gc() {
        if (!fileManager.fileExistsAtPath(root)) return

        val now = NSDate().timeIntervalSince1970
        val sevenDaysSec = 7.0 * 24 * 60 * 60
        val maxBytes = 100L * 1024 * 1024

        val allFiles = walkFiles(root)

        // 1) 删 7 天前
        val survivors = mutableListOf<FileInfo>()
        for (f in allFiles) {
            val ageSec = now - f.mtimeEpochSec
            if (ageSec > sevenDaysSec) {
                fileManager.removeItemAtPath(f.path, error = null)
            } else {
                survivors += f
            }
        }

        // 2) 总量 > 100MB 时按 mtime 从旧到新淘汰(删失败不减 quota,对齐 android round-2 修正)
        val totalNow = survivors.sumOf { it.sizeBytes }
        if (totalNow <= maxBytes) return

        var remaining = totalNow
        survivors.sortedBy { it.mtimeEpochSec }.forEach { f ->
            if (remaining <= maxBytes) return@forEach
            val size = f.sizeBytes
            val deleted = fileManager.removeItemAtPath(f.path, error = null)
            if (deleted) remaining -= size
        }
    }

    private data class FileInfo(
        val path: String,
        val sizeBytes: Long,
        val mtimeEpochSec: Double,
    )

    /**
     * 递归枚举 `dir` 下所有普通文件。
     *
     * 判目录用 "能否再列子内容":若 `contentsOfDirectoryAtPath` 返回非 null 视为目录递归下去,
     * 否则当文件处理并读 attrs 里的 size / mtime。这样避免额外解析 NSFileType 常量。
     */
    private fun walkFiles(dir: String): List<FileInfo> {
        val result = mutableListOf<FileInfo>()
        val entries = fileManager.contentsOfDirectoryAtPath(dir, error = null) ?: return emptyList()
        for (entry in entries) {
            val name = entry as? String ?: continue
            val fullPath = (dir as NSString).stringByAppendingPathComponent(name)
            val subEntries = fileManager.contentsOfDirectoryAtPath(fullPath, error = null)
            if (subEntries != null) {
                result += walkFiles(fullPath)
                continue
            }
            val attrs = fileManager.attributesOfItemAtPath(fullPath, error = null) ?: continue
            val size = (attrs[NSFileSize] as? Number)?.toLong() ?: 0L
            val mtime = (attrs[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0
            result += FileInfo(path = fullPath, sizeBytes = size, mtimeEpochSec = mtime)
        }
        return result
    }

    private fun ensureDir(path: String) {
        if (fileManager.fileExistsAtPath(path)) return
        fileManager.createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    private fun todayFolder(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val y = now.year.toString().padStart(4, '0')
        val m = now.monthNumber.toString().padStart(2, '0')
        val d = now.dayOfMonth.toString().padStart(2, '0')
        return "$y-$m-$d"
    }

    private fun ByteArray.toNSData(): NSData = if (this.isEmpty()) {
        NSData.create(bytes = null, length = 0uL)
    } else {
        this.usePinned { pinned ->
            NSData.dataWithBytes(bytes = pinned.addressOf(0), length = this.size.toULong())
        }
    }
}

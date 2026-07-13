package com.uvp.sim.recording

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * cross-review R1 #5 拆分 step 2(from [IosRecordingService]):
 * 文件路径 + index.json 持久化 + 输出路径生成。
 *
 * 目录 layout:
 * ```
 * <Documents>/recordings/
 *   index.json                              — RecordingIndexFile JSON
 *   <deviceId>/<YYYYMMDD>/<HHmmss>.mp4     — 单段录像
 * ```
 *
 * 无状态运行时字段(全部是 lazy 缓存 + 无副作用函数),一实例一 recording service。
 */
@OptIn(ExperimentalForeignApi::class)
internal class RecordingFileStore(
    private val deviceIdSupplier: () -> String,
    private val timeZone: TimeZone,
) {

    private val documentsDir: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        )
        (paths.firstOrNull() as? String) ?: "/tmp"
    }

    val baseDir: String by lazy {
        val p = (documentsDir as NSString).stringByAppendingPathComponent("recordings")
        NSFileManager.defaultManager.ensureDir(p)
        p
    }

    val indexFilePath: String by lazy {
        (baseDir as NSString).stringByAppendingPathComponent("index.json")
    }

    /**
     * 读 index.json,解码为 RecordingFile 列表。文件不存在返回 emptyList。
     * IO 走 [Dispatchers.Default],读取 + parse 失败静默 emit warning + 返回 emptyList。
     */
    suspend fun loadIndex(): List<RecordingFile> {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(indexFilePath)) return emptyList()
        return runCatching {
            val text = withContext(Dispatchers.Default) {
                NSString.stringWithContentsOfFile(
                    path = indexFilePath,
                    encoding = NSUTF8StringEncoding,
                    error = null,
                ) as? String
            } ?: ""
            RecordingIndex.decode(text).files
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_RECORDING_LOAD_FAIL msg=${it.message}",
            )
        }.getOrDefault(emptyList())
    }

    /**
     * 写 index.json(atomic)。失败静默 emit warning,不抛。
     */
    fun persistIndex(idx: RecordingIndexFile) {
        runCatching {
            val json = RecordingIndex.encode(idx)
            val ok = (json as NSString).writeToFile(
                path = indexFilePath,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null,
            )
            if (!ok) {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "IOS_RECORDING_INDEX_PERSIST_FAIL path=$indexFilePath reason=write_returned_false count=${idx.files.size}",
                )
            } else {
                SystemLogger.emit(
                    LogLevel.Debug, LogTag.Media,
                    "IOS_RECORDING_INDEX_PERSIST_OK path=$indexFilePath count=${idx.files.size}",
                )
            }
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_RECORDING_INDEX_PERSIST_FAIL msg=${it.message}",
            )
        }
    }

    /**
     * 按 [instant] + 当前 deviceId 生成本次录像的 output 路径。
     * `<baseDir>/<deviceId>/<YYYYMMDD>/<HHmmss>.mp4`
     */
    fun newOutputPath(instant: Instant): String {
        val ldt = instant.toLocalDateTime(timeZone)
        val ymd = pad4(ldt.year) + pad2(ldt.monthNumber) + pad2(ldt.dayOfMonth)
        val hms = pad2(ldt.hour) + pad2(ldt.minute) + pad2(ldt.second)
        val deviceDir = (baseDir as NSString).stringByAppendingPathComponent(deviceIdSupplier())
        val dayDir = (deviceDir as NSString).stringByAppendingPathComponent(ymd)
        return (dayDir as NSString).stringByAppendingPathComponent("$hms.mp4")
    }

    /** 递归建父目录(NSFileManager 内部幂等)。 */
    fun ensureParentDir(filePath: String) {
        val parent = deletingLastPathComponent(filePath)
        NSFileManager.defaultManager.ensureDir(parent)
    }

    /** 读文件大小,失败或不存在返回 0。 */
    fun fileSizeBytes(path: String): Long {
        val fm = NSFileManager.defaultManager
        val attrs = fm.attributesOfItemAtPath(path, error = null) ?: return 0L
        val v = attrs["NSFileSize"] as? NSNumber
        return v?.longLongValue ?: 0L
    }

    /** 删单个文件(缺失 / 权限失败 静默,由上层判断)。 */
    suspend fun deleteFile(path: String) {
        withContext(Dispatchers.Default) {
            runCatching {
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            }
        }
    }
}

private fun deletingLastPathComponent(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx <= 0) path else path.substring(0, idx)
}

private fun pad2(v: Int): String = v.toString().padStart(2, '0')
private fun pad4(v: Int): String = v.toString().padStart(4, '0')

@OptIn(ExperimentalForeignApi::class)
private fun NSFileManager.ensureDir(path: String) {
    if (!fileExistsAtPath(path)) {
        createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }
}

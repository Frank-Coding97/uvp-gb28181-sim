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
import platform.Foundation.NSUUID
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
     *
     * 磁盘上的 filePath / thumbnailPath 存**相对 [baseDir] 的路径**(见 [persistIndex]),
     * loadIndex 时统一 rebase 到当前 baseDir 拼绝对路径给上层。
     *
     * 老 index.json 里存的是绝对路径(v1.1 早期实现),loadIndex 时如果发现是"/"开头的
     * 绝对路径,尝试 stripBaseDir 转相对(命中当前 baseDir);拿不到就原样保留(如老包
     * 沙盒 UUID 已变的情况,原路径就是失效的,继续给绝对路径 UI 也拿不到 —— 数据是死的,
     * 但至少不 crash,delete 时靠 id 匹配还能清除索引)。老绝对路径在下次 persistIndex
     * 时被 [toRelative] 转成相对,完成一次隐式迁移,后续沙盒 rebase 就不再失效。
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
            RecordingIndex.decode(text).files.map { it.rebaseToAbsolute() }
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_RECORDING_LOAD_FAIL msg=${it.message}",
            )
        }.getOrDefault(emptyList())
    }

    /**
     * 写 index.json(atomic)。返回是否成功。
     *
     * 落盘前把 filePath / thumbnailPath 从绝对路径 → 相对 [baseDir] 的路径(见 [toRelative]),
     * 抵御 iOS 沙盒 container UUID 变化(卸载重装 / debug 重装 / bundle 迁移)导致的
     * 绝对路径失效问题 —— 相对路径在下次 loadIndex 时用当前 baseDir 重新拼绝对,
     * 天然跟着新沙盒走。
     *
     * cross-review R1 #4:失败必须能被上层感知(而不是仅 log warning),否则
     * `finalizeWriterLocked` 会在 index 未落盘的情况下把 recording 标"已保存"回给 UI,
     * 重启后 index.json 里没这条 → 用户可见录像丢失。返回 false 时调用方应把 state 标 Failed。
     */
    fun persistIndex(idx: RecordingIndexFile): Boolean {
        return runCatching {
            val relativized = idx.copy(files = idx.files.map { it.stripToRelative() })
            val json = RecordingIndex.encode(relativized)
            val ok = (json as NSString).writeToFile(
                path = indexFilePath,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null,
            )
            if (!ok) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_RECORDING_INDEX_PERSIST_FAIL path=$indexFilePath reason=write_returned_false count=${idx.files.size}",
                )
                return@runCatching false
            }
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_RECORDING_INDEX_PERSIST_OK path=$indexFilePath count=${idx.files.size}",
            )
            true
        }.getOrElse {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_RECORDING_INDEX_PERSIST_FAIL msg=${it.message}",
            )
            false
        }
    }

    /**
     * 把绝对路径转成相对 [baseDir] 的路径。
     *
     * 相对格式统一为 `<deviceId>/<ymd>/<hms>.mp4`(不含 "recordings/" 段,那是 baseDir 的一部分),
     * 保证 [toAbsolute] 拼回时不会出现 "recordings/recordings/" 双前缀。
     *
     * 规则:
     *   - 命中当前 baseDir 前缀 → 截去 `<baseDir>/`,返回相对
     *   - 已经是相对路径(不以"/"开头)→ 原样返回(幂等)
     *   - 绝对但不命中 baseDir(比如老沙盒 UUID 的绝对路径)→ 按 "/recordings/" 段截取,
     *     取其**后面**的部分(即 "recordings/" 之后的相对段)。典型形式
     *     `/var/mobile/Containers/Data/Application/<旧UUID>/Documents/recordings/xxx` → `xxx`,
     *     跨沙盒复用当前 baseDir 时能正确拼绝对。
     *   - 全都不命中 → 原样保留(该数据本就失效,不做假迁移)
     */
    internal fun toRelative(absolute: String): String {
        if (!absolute.startsWith("/")) return absolute
        val prefix = "$baseDir/"
        if (absolute.startsWith(prefix)) return absolute.removePrefix(prefix)
        val marker = "/recordings/"
        val idx = absolute.indexOf(marker)
        if (idx >= 0) return absolute.substring(idx + marker.length)
        return absolute
    }

    /** 相对路径拼当前 baseDir 前缀,已是绝对路径原样返回(幂等)。 */
    internal fun toAbsolute(relativeOrAbsolute: String): String {
        if (relativeOrAbsolute.startsWith("/")) return relativeOrAbsolute
        return (baseDir as NSString).stringByAppendingPathComponent(relativeOrAbsolute)
    }

    /**
     * loadIndex 时把磁盘上的路径 rebase 到当前 baseDir 的绝对路径。
     *
     * **先 toRelative 归一化再 toAbsolute**:磁盘上可能是相对(新版本)或绝对(老版本,沙盒
     * UUID 已换 → UUID 失效)。toRelative 会把老绝对路径的 UUID 段剥掉、只留 recordings 后的
     * 相对段,再 toAbsolute 拼回当前 baseDir。跳过 toRelative 就会让老 UUID 原样透传
     * (因为 toAbsolute 见 "/" 开头直接 return),用户升级 App / 卸载重装后路径依然失效。
     */
    private fun RecordingFile.rebaseToAbsolute(): RecordingFile = copy(
        filePath = toAbsolute(toRelative(filePath)),
        thumbnailPath = thumbnailPath?.let { toAbsolute(toRelative(it)) },
    )

    private fun RecordingFile.stripToRelative(): RecordingFile = copy(
        filePath = toRelative(filePath),
        thumbnailPath = thumbnailPath?.let { toRelative(it) },
    )

    /**
     * 按 [instant] + 当前 deviceId 生成本次录像的 output 路径。
     * `<baseDir>/<deviceId>/<YYYYMMDD>/<HHmmss>-<uuid8>.mp4`
     *
     * 追加 8 位 UUID 前缀防同秒碰撞:快速 stop/start、segment rollover 与用户操作竞态
     * 都可能在同一秒生成两次输出,单纯 HHmmss 会撞名 → AVAssetWriter 复写失败,索引里
     * 两条录像指向同路径。UUID 使命名字典序仍按秒排序,但同秒内互不冲突。
     */
    fun newOutputPath(instant: Instant): String {
        val ldt = instant.toLocalDateTime(timeZone)
        val ymd = pad4(ldt.year) + pad2(ldt.monthNumber) + pad2(ldt.dayOfMonth)
        val hms = pad2(ldt.hour) + pad2(ldt.minute) + pad2(ldt.second)
        val suffix = shortUuid()
        val deviceDir = (baseDir as NSString).stringByAppendingPathComponent(deviceIdSupplier())
        val dayDir = (deviceDir as NSString).stringByAppendingPathComponent(ymd)
        return (dayDir as NSString).stringByAppendingPathComponent("$hms-$suffix.mp4")
    }

    /** 8 位 lowercase hex 短 UUID(取 NSUUID.UUIDString 前 8 位),用于文件后缀防碰撞。 */
    private fun shortUuid(): String {
        val u = NSUUID().UUIDString().replace("-", "").lowercase()
        return if (u.length >= 8) u.substring(0, 8) else u
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

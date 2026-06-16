package com.uvp.sim.recording

import android.content.Context
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/**
 * AndroidRecordingService — OsdRendererHolder + MediaCodec + MediaMuxer 实现。
 *
 * 跟工业 IPC 同构架构(2026-06-14 重构):
 * - 不再用 CameraX VideoCapture<Recorder>(黑盒,绕过 OsdRenderer,mp4 无 OSD)
 * - 改用 [OsdRecordingPipeline]:OsdRendererHolder.addEncoderSurface 注册录像 encoder,
 *   跟直播共享单一画面源,mp4 自带 OSD,WVP 回放天然继承戳
 *
 * 保留原有职责:
 * - 文件路径:`<filesDir>/recordings/<deviceId>/<YYYYMMDD>/<HHmmss>.mp4`
 * - index.json:`<filesDir>/recordings/index.json`,孤儿文件扫描 + 自愈
 * - segmentMinutes 切片接力(默认 30 分钟)
 * - minFreeMb 磁盘检查(默认 200MB)
 * - 缩略图异步抽取
 *
 * 分辨率约束(2026-06-14):
 * - 录像分辨率 = 直播分辨率(SimConfig.video),由 [encoderConfigSupplier] 提供
 * - 跟工业 IPC sensor → ISP region → 共享画面源 同构,
 *   各消费者独立分辨率 P1-D 后续支持(letterbox)
 * - profile.quality 字段保留为占位,实际不读
 *
 * 依赖:
 * - [osdConfigSupplier] OSD 实时配置(UI 改字段反映到下一帧)
 * - [encoderConfigSupplier] (width, height, frameRate, bitrateBps, gopSec)
 * - 不再依赖 streamerSupplier(VideoCapture 路径已删)
 */
class AndroidRecordingService(
    private val context: Context,
    private val executor: Executor,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val osdConfigSupplier: () -> StateFlow<OsdConfig>,
    private val encoderConfigSupplier: () -> EncoderConfig,
    private val profile: com.uvp.sim.config.RecordingProfile = com.uvp.sim.config.RecordingProfile(),
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : RecordingService {

    /** 录像 encoder 配置 — 跟直播 SimConfig.video 共享。 */
    data class EncoderConfig(
        val widthPx: Int,
        val heightPx: Int,
        val frameRate: Int,
        val bitrateBps: Int,
        val keyframeIntervalSeconds: Int
    )

    private val mutex = Mutex()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _files = MutableStateFlow<List<RecordingFile>>(emptyList())
    override val files: StateFlow<List<RecordingFile>> = _files.asStateFlow()

    private var pipeline: OsdRecordingPipeline? = null
    private var activeOutputFile: File? = null
    private var activeChannelId: String? = null
    private var activeStartMs: Long = 0L
    private var activeSegmentIndex: Int = 0
    private var activeSource: RecordSource = RecordSource.Manual
    private var thumbJob: Job? = null
    private var guardJob: Job? = null
    @Volatile private var pendingSegmentSplit: Boolean = false

    private val baseDir: File by lazy {
        File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }
    }
    private val indexFile: File by lazy { File(baseDir, "index.json") }

    override suspend fun load() = mutex.withLock {
        runCatching {
            if (indexFile.exists()) {
                val json = withContext(Dispatchers.IO) { indexFile.readText() }
                _files.value = RecordingIndex.decode(json).files
            } else {
                _files.value = emptyList()
            }
        }.onFailure {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "录像索引加载失败 → ${it.message}")
        }
        runCatching { reconcileOrphans() }.onFailure {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "扫孤儿文件失败: ${it.message}")
        }
        Unit
    }

    private suspend fun reconcileOrphans() = withContext(Dispatchers.IO) {
        val deviceDir = File(baseDir, deviceId)
        if (!deviceDir.exists()) return@withContext
        val indexed = _files.value.map { it.filePath }.toSet()
        val orphans = mutableListOf<RecordingFile>()
        var cleaned = 0
        deviceDir.listFiles()?.forEach { dayDir ->
            if (!dayDir.isDirectory) return@forEach
            dayDir.listFiles { _, name -> name.endsWith(".mp4") }?.forEach { mp4 ->
                if (mp4.absolutePath in indexed) return@forEach
                if (mp4.length() == 0L) {
                    runCatching { mp4.delete() }
                    runCatching { File(mp4.absolutePath.removeSuffix(".mp4") + ".jpg").delete() }
                    cleaned += 1
                    return@forEach
                }
                val durationMs = runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(mp4.absolutePath)
                        mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    } finally {
                        runCatching { mmr.release() }
                    }
                }.getOrDefault(0L)
                if (durationMs <= 0L) {
                    runCatching { mp4.delete() }
                    runCatching { File(mp4.absolutePath.removeSuffix(".mp4") + ".jpg").delete() }
                    cleaned += 1
                    return@forEach
                }
                val endMs = mp4.lastModified()
                val startMs = endMs - durationMs
                val thumb = File(mp4.absolutePath.removeSuffix(".mp4") + ".jpg")
                orphans += RecordingFile(
                    id = UUID.randomUUID().toString(),
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    durationMs = durationMs,
                    channelId = activeChannelId ?: deviceId,
                    filePath = mp4.absolutePath,
                    sizeBytes = mp4.length(),
                    thumbnailPath = if (thumb.exists()) thumb.absolutePath else null,
                    source = RecordSource.Manual,
                    type = RecordType.Time,
                    secrecy = 0
                )
            }
        }
        if (orphans.isNotEmpty() || cleaned > 0) {
            val merged = _files.value + orphans
            _files.value = merged
            persistIndex(RecordingIndexFile(files = merged))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "启动一致性检查 → 补回 ${orphans.size} 段,清理 $cleaned 个损坏文件"
            )
        }
    }

    override suspend fun start(source: RecordSource, channelId: String): Result<Unit> {
        mutex.withLock {
            val current = _state.value
            if (current is RecordingState.Recording) {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "录像已在进行中,忽略重复指令 source=$source"
                )
                return Result.success(Unit)
            }
        }
        val minFreeBytes = profile.minFreeMb.toLong() * 1024 * 1024
        val freeBytes = baseDir.usableSpace
        if (freeBytes < minFreeBytes) {
            val msg = "磁盘空间不足 (${freeBytes / 1024 / 1024}MB < ${profile.minFreeMb}MB),拒绝录像"
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, msg)
            _state.value = RecordingState.Failed(msg)
            return Result.failure(IllegalStateException(msg))
        }
        return runCatching {
            val now = clock.now()
            val outputFile = newOutputFile(now)
            outputFile.parentFile?.mkdirs()
            val encCfg = encoderConfigSupplier()
            val newPipeline = OsdRecordingPipeline(
                context = context,
                osdConfigFlow = osdConfigSupplier(),
                widthPx = encCfg.widthPx,
                heightPx = encCfg.heightPx,
                frameRate = encCfg.frameRate,
                bitrateBps = encCfg.bitrateBps,
                keyframeIntervalSeconds = encCfg.keyframeIntervalSeconds,
                outputFile = outputFile
            )
            newPipeline.start { finalFile, error ->
                handlePipelineFinalize(finalFile, error)
            }
            pipeline = newPipeline
            activeOutputFile = outputFile
            activeChannelId = channelId
            activeStartMs = now.toEpochMilliseconds()
            activeSource = source
            if (!pendingSegmentSplit) activeSegmentIndex = 0
            pendingSegmentSplit = false
            _state.value = RecordingState.Recording(
                startMs = activeStartMs,
                segmentIndex = activeSegmentIndex,
                source = source
            )
            guardJob?.cancel()
            guardJob = scope.launch { runGuard(channelId, source) }
            runCatching { RecordingForegroundService.start(context) }
                .onFailure {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "前台 Service 启动失败(降级:仅前台可录): ${it.message}"
                    )
                }
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "开始录像(OSD pipeline) → source=$source channel=$channelId path=${outputFile.absolutePath}"
            )
        }.recoverCatching {
            _state.value = RecordingState.Failed(it.message ?: "unknown")
            SystemLogger.emit(LogLevel.Error, LogTag.Media, "录像启动失败: ${it.message}")
            throw it
        }
    }

    override suspend fun stop(): Result<RecordingFile?> {
        val pipe = pipeline ?: return Result.success(null)
        return runCatching {
            val previous = _state.value as? RecordingState.Recording
            if (previous != null) {
                _state.value = RecordingState.Stopping(previous, reason = "user_stop")
            }
            pendingSegmentSplit = false
            guardJob?.cancel()
            guardJob = null
            pipe.stop()
            null  // finalize 异步走 handlePipelineFinalize
        }
    }

    private suspend fun runGuard(channelId: String, source: RecordSource) {
        val segmentMs = profile.segmentMinutes.toLong() * 60 * 1000
        val minFreeBytes = profile.minFreeMb.toLong() * 1024 * 1024
        try {
            while (true) {
                kotlinx.coroutines.delay(GUARD_TICK_MS)
                if (pipeline == null) return
                val started = activeStartMs
                val nowMs = clock.now().toEpochMilliseconds()
                val recordedMs = nowMs - started

                if (segmentMs > 0 && recordedMs >= segmentMs) {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "录像满 ${profile.segmentMinutes} 分钟,触发切片"
                    )
                    pendingSegmentSplit = true
                    val pipe = pipeline ?: return
                    runCatching { pipe.stop() }
                    return
                }

                val free = baseDir.usableSpace
                if (free < minFreeBytes) {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "磁盘剩余 ${free / 1024 / 1024}MB < ${profile.minFreeMb}MB,主动停止录像"
                    )
                    pendingSegmentSplit = false
                    val pipe = pipeline ?: return
                    runCatching { pipe.stop() }
                    return
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // stop / finalize 路径正常 cancel
        }
    }

    override suspend fun delete(id: String): Result<Unit> = mutex.withLock {
        runCatching {
            val target = _files.value.firstOrNull { it.id == id } ?: return@runCatching
            withContext(Dispatchers.IO) {
                runCatching { File(target.filePath).delete() }
                target.thumbnailPath?.let { runCatching { File(it).delete() } }
            }
            val updated = RecordingIndex.remove(
                RecordingIndexFile(files = _files.value),
                id
            )
            _files.value = updated.files
            persistIndex(updated)
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "录像已删除 → $id")
        }
    }

    /**
     * pipeline finalize 回调 — encoder 报 EOS / error 后触发。
     *
     * 跟原 handleRecordEvent.Finalize 等价:
     * - 成功:加 index + 持久化 + 异步抽缩略图,接力切片(if pendingSegmentSplit)
     * - 失败:状态进 Failed,emit 错误日志
     */
    private fun handlePipelineFinalize(finalFile: File?, error: Throwable?) {
        val isSplit = pendingSegmentSplit
        if (!isSplit) {
            runCatching { RecordingForegroundService.stop(context) }
            guardJob?.cancel()
            guardJob = null
        }
        val started = activeStartMs
        val channel = activeChannelId
        val source = activeSource
        pipeline = null
        activeOutputFile = null

        if (error != null || finalFile == null || channel == null || started == 0L) {
            val reason = error?.message ?: "finalize error: file=$finalFile channel=$channel"
            pendingSegmentSplit = false
            scope.launch {
                mutex.withLock {
                    _state.value = RecordingState.Failed(reason)
                }
                SystemLogger.emit(LogLevel.Error, LogTag.Media, "录像 finalize 失败: $reason")
            }
            return
        }

        val endMs = clock.now().toEpochMilliseconds()
        val durationMs = endMs - started
        val sizeBytes = finalFile.length()
        val recordingFile = RecordingFile(
            id = UUID.randomUUID().toString(),
            startTimeMs = started,
            endTimeMs = endMs,
            durationMs = durationMs,
            channelId = channel,
            filePath = finalFile.absolutePath,
            sizeBytes = sizeBytes,
            thumbnailPath = null,
            source = source,
            type = RecordType.Time,
            secrecy = 0
        )
        scope.launch {
            mutex.withLock {
                val current = _files.value
                val updated = RecordingIndexFile(files = current + recordingFile)
                _files.value = updated.files
                persistIndex(updated)
                if (!isSplit) {
                    _state.value = RecordingState.Idle
                }
            }
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                if (isSplit) "切片完成 → 文件=${finalFile.name} 时长=${durationMs}ms,接力下一段"
                else "停止录像 → 文件=${finalFile.name} 时长=${durationMs}ms 大小=${sizeBytes}B"
            )
            thumbJob = scope.launch(Dispatchers.IO) {
                val thumbPath = ThumbnailExtractor.extract(finalFile.absolutePath, durationMs)
                if (thumbPath != null) {
                    mutex.withLock {
                        val list = _files.value.map {
                            if (it.id == recordingFile.id) it.copy(thumbnailPath = thumbPath)
                            else it
                        }
                        _files.value = list
                        persistIndex(RecordingIndexFile(files = list))
                    }
                }
            }
            if (isSplit) {
                activeSegmentIndex += 1
                val resumeResult = runCatching { start(source, channel) }
                if (resumeResult.isFailure) {
                    pendingSegmentSplit = false
                    SystemLogger.emit(
                        LogLevel.Error, LogTag.Media,
                        "切片接力启动失败: ${resumeResult.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }

    private fun newOutputFile(instant: Instant): File {
        val ldt = instant.toLocalDateTime(timeZone)
        val ymd = "%04d%02d%02d".format(ldt.year, ldt.monthNumber, ldt.dayOfMonth)
        val hms = "%02d%02d%02d".format(ldt.hour, ldt.minute, ldt.second)
        val dir = File(File(baseDir, deviceId), ymd)
        return File(dir, "$hms.mp4")
    }

    private fun persistIndex(idx: RecordingIndexFile) {
        runCatching {
            val json = RecordingIndex.encode(idx)
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(json)
        }.onFailure {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "录像索引持久化失败: ${it.message}")
        }
    }

    private fun emitState(update: (RecordingState) -> RecordingState) {
        _state.update(update)
    }

    companion object {
        const val MIN_FREE_BYTES: Long = 200L * 1024 * 1024
        const val GUARD_TICK_MS: Long = 30L * 1000
    }
}

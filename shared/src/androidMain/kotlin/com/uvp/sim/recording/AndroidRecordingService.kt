package com.uvp.sim.recording

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.core.CameraSelector
import androidx.lifecycle.LifecycleOwner
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
 * AndroidRecordingService — CameraX VideoCapture<Recorder> 实现。
 *
 * 关键点(plan §4 / §5):
 *   - 文件路径:`<filesDir>/recordings/<deviceId>/<YYYYMMDD>/<HHmmss>.mp4`
 *   - index.json:`<filesDir>/recordings/index.json`,挂在 deviceId 上
 *   - 30 分钟 timer 切片(后续 R04+:目前只做手动起停,timer 留扩展)
 *   - 录像与实时推流互斥(plan §5),由 SimulatorEngine 在 handleInvite 把住
 *
 * Lifecycle / provider 共享(2026-06-13 修):
 *   - [ProcessCameraProvider] 是进程单例 — 录像和 streamer 必须共用
 *   - 通过 [cameraOwnerSupplier] 获取 streamer 自驱的 STARTED LifecycleOwner,
 *     避免 Activity onStop 把录像一起干掉(切后台不录的根因)
 *   - 用 `provider.unbind(videoCapture)` 而非 `unbindAll()`,不影响 streamer 的
 *     Preview / EncoderPreview(预览黑屏的根因)
 *
 * 异常处理:
 *   - bindToLifecycle 失败 → 状态进 Failed,UI toast
 *   - Recorder 抛异常 → 通过 VideoRecordEvent.Finalize.hasError 反馈
 *
 * 缩略图抽取:finalize 后异步走 [ThumbnailExtractor],失败不阻塞。
 */
class AndroidRecordingService(
    private val context: Context,
    private val streamerSupplier: () -> com.uvp.sim.camera.AndroidCameraStreamer,
    private val executor: Executor,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val profile: com.uvp.sim.config.RecordingProfile = com.uvp.sim.config.RecordingProfile(),
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : RecordingService {

    private val mutex = Mutex()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _files = MutableStateFlow<List<RecordingFile>>(emptyList())
    override val files: StateFlow<List<RecordingFile>> = _files.asStateFlow()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var activeOutputFile: File? = null
    private var activeChannelId: String? = null
    private var activeStartMs: Long = 0L
    private var activeSegmentIndex: Int = 0
    private var activeSource: RecordSource = RecordSource.Manual
    private var thumbJob: Job? = null
    /** 守护协程:30 分钟切片 + 滚动磁盘检查。start 时拉起,stop/finalize 时取消。 */
    private var guardJob: Job? = null
    /** 切片到来时,handleRecordEvent finalize 后由这个标志驱动接力 start 下一段。 */
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
        // 扫孤儿文件:进程被杀 / OOM 后,mp4 还在但 index 漏记。
        // 把不在 index 里的 mp4 补回(用 MediaMetadataRetriever 拿时长),
        // size=0 的视为录到一半就崩,直接清掉。
        runCatching { reconcileOrphans() }.onFailure {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "扫孤儿文件失败: ${it.message}")
        }
        Unit
    }

    /**
     * 启动时一致性检查:
     *   - mp4 大小为 0 → 录到一半进程死,删文件 + 同名 jpg
     *   - mp4 不在 index → 用 MediaMetadataRetriever 抽时长,补回 index
     */
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
                // 用 MediaMetadataRetriever 拿真实时长(避免猜)
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
                    // 文件存在但 demuxer 读不出时长 → 不可恢复,删除
                    runCatching { mp4.delete() }
                    runCatching { File(mp4.absolutePath.removeSuffix(".mp4") + ".jpg").delete() }
                    cleaned += 1
                    return@forEach
                }
                // 起始时间用 mtime 倒推:endTimeMs = mtime, startTimeMs = mtime - durationMs
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
        // 磁盘兜底:可用空间 < 配置阈值不开录(默认 200MB,1080p 4Mbps 一分钟 ≈30MB)
        val minFreeBytes = profile.minFreeMb.toLong() * 1024 * 1024
        val freeBytes = baseDir.usableSpace
        if (freeBytes < minFreeBytes) {
            val msg = "磁盘空间不足 (${freeBytes / 1024 / 1024}MB < ${profile.minFreeMb}MB),拒绝录像"
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, msg)
            _state.value = RecordingState.Failed(msg)
            return Result.failure(IllegalStateException(msg))
        }
        return runCatching {
            val capture = rebindFreshVideoCapture()
            val now = clock.now()
            val outputFile = newOutputFile(now)
            outputFile.parentFile?.mkdirs()
            val recorder = capture.output
            val pendingRecording = recorder.prepareRecording(
                context,
                FileOutputOptions.Builder(outputFile).build()
            )
            val recording = pendingRecording.start(executor) { event ->
                handleRecordEvent(event)
            }
            activeRecording = recording
            activeOutputFile = outputFile
            activeChannelId = channelId
            activeStartMs = now.toEpochMilliseconds()
            activeSource = source
            // 切片接力时(pendingSegmentSplit=true)保留 segmentIndex 自增;否则归零。
            if (!pendingSegmentSplit) activeSegmentIndex = 0
            pendingSegmentSplit = false
            _state.value = RecordingState.Recording(
                startMs = activeStartMs,
                segmentIndex = activeSegmentIndex,
                source = source
            )
            // 守护协程:30 分钟切片 + 滚动磁盘检查
            guardJob?.cancel()
            guardJob = scope.launch { runGuard(channelId, source) }
            // 拉起前台 Service:Android 14+ 后台录视频必须的保活手段
            runCatching { RecordingForegroundService.start(context) }
                .onFailure {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "前台 Service 启动失败(降级:仅前台可录): ${it.message}"
                    )
                }
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "开始录像 → source=$source channel=$channelId path=${outputFile.absolutePath}"
            )
        }.recoverCatching {
            _state.value = RecordingState.Failed(it.message ?: "unknown")
            SystemLogger.emit(LogLevel.Error, LogTag.Media, "录像启动失败: ${it.message}")
            throw it
        }
    }

    override suspend fun stop(): Result<RecordingFile?> {
        val recording = activeRecording ?: return Result.success(null)
        return runCatching {
            val previous = _state.value as? RecordingState.Recording
            if (previous != null) {
                _state.value = RecordingState.Stopping(previous, reason = "user_stop")
            }
            // 用户主动停 → 不再接力切片
            pendingSegmentSplit = false
            guardJob?.cancel()
            guardJob = null
            recording.stop()
            null  // 实际 finalize 在 handleRecordEvent 里
        }
    }

    /**
     * 录像守护协程 — 跟单段录像同生命周期(由 [start] 拉起,[stop]/finalize 终止)。
     *
     * 职责:
     *   1. 每分钟扫一次磁盘,跌破 [MIN_FREE_BYTES] 主动 stop,避免半截损坏录像
     *   2. 当前段录到 [SEGMENT_DURATION_MS] 时触发切片(set pendingSegmentSplit + recording.stop)
     *      finalize 回调里看到 pendingSegmentSplit=true 就接力 start 下一段
     *
     * 用 delay(30_000) 粒度即可,30s 误差不影响切片节奏。
     */
    private suspend fun runGuard(channelId: String, source: RecordSource) {
        val segmentMs = profile.segmentMinutes.toLong() * 60 * 1000
        val minFreeBytes = profile.minFreeMb.toLong() * 1024 * 1024
        try {
            while (true) {
                kotlinx.coroutines.delay(GUARD_TICK_MS)
                if (activeRecording == null) return
                val started = activeStartMs
                val nowMs = clock.now().toEpochMilliseconds()
                val recordedMs = nowMs - started

                // 切片(profile.segmentMinutes <= 0 时关闭切片)
                if (segmentMs > 0 && recordedMs >= segmentMs) {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "录像满 ${profile.segmentMinutes} 分钟,触发切片"
                    )
                    pendingSegmentSplit = true
                    val rec = activeRecording ?: return
                    runCatching { rec.stop() }
                    return
                }

                // 滚动磁盘检查
                val free = baseDir.usableSpace
                if (free < minFreeBytes) {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "磁盘剩余 ${free / 1024 / 1024}MB < ${profile.minFreeMb}MB,主动停止录像"
                    )
                    pendingSegmentSplit = false
                    val rec = activeRecording ?: return
                    runCatching { rec.stop() }
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

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> Unit
            is VideoRecordEvent.Status -> Unit
            is VideoRecordEvent.Finalize -> {
                val isSplit = pendingSegmentSplit
                // 切片接力时不停前台 Service — 下一段录像马上接上
                if (!isSplit) {
                    runCatching { RecordingForegroundService.stop(context) }
                    guardJob?.cancel()
                    guardJob = null
                }
                val outputFile = activeOutputFile
                val started = activeStartMs
                val channel = activeChannelId
                val source = activeSource
                activeRecording = null
                activeOutputFile = null
                if (event.hasError() || outputFile == null || channel == null || started == 0L) {
                    val reason = event.cause?.message ?: "finalize error code=${event.error}"
                    pendingSegmentSplit = false  // 接力中失败,不接了
                    scope.launch {
                        mutex.withLock {
                            _state.value = RecordingState.Failed(reason)
                        }
                        SystemLogger.emit(LogLevel.Error, LogTag.Media, "录像 finalize 失败: $reason")
                    }
                } else {
                    val endMs = clock.now().toEpochMilliseconds()
                    val durationMs = endMs - started
                    val sizeBytes = outputFile.length()
                    val recordingFile = RecordingFile(
                        id = UUID.randomUUID().toString(),
                        startTimeMs = started,
                        endTimeMs = endMs,
                        durationMs = durationMs,
                        channelId = channel,
                        filePath = outputFile.absolutePath,
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
                            // 切片接力:不进 Idle,下一段录像马上启动
                            if (!isSplit) {
                                _state.value = RecordingState.Idle
                            }
                        }
                        SystemLogger.emit(
                            LogLevel.Info, LogTag.Media,
                            if (isSplit) "切片完成 → 文件=${outputFile.name} 时长=${durationMs}ms,接力下一段"
                            else "停止录像 → 文件=${outputFile.name} 时长=${durationMs}ms 大小=${sizeBytes}B"
                        )
                        thumbJob = scope.launch(Dispatchers.IO) {
                            val thumbPath = ThumbnailExtractor.extract(outputFile.absolutePath, durationMs)
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
                        // 接力启动下一段
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
            }
        }
    }

    /**
     * 每次 start() 时调用,重新构造 Recorder + VideoCapture 并 rebind 到 streamer 的
     * 自驱 LifecycleOwner。
     *
     * CameraX 硬约束(踩过的坑):
     *   - `Recorder` 实例只能录一次,完成后 prepareRecording().start() 会立刻
     *     finalize 报 "Recording was stopped before any data could be produced"
     *   - 解法:每次都建新 Recorder + VideoCapture,**只 unbind 上一个 VideoCapture**
     *     (不能 unbindAll,会一并干掉 streamer 的 Preview)
     *
     * 共享决策:provider 与 lifecycleOwner 都来自 streamer.cameraOwnerSupplier,
     * 切后台 / Activity 重建时 streamer 的自驱 lifecycle 仍 STARTED,录像不被
     * 系统自动 unbind。
     */
    private suspend fun rebindFreshVideoCapture(): VideoCapture<Recorder> {
        val (provider, lifecycleOwner) = streamerSupplier().awaitCameraOwner()
        val quality = when (profile.quality.uppercase()) {
            "SD" -> Quality.SD
            "HD" -> Quality.HD
            "FHD" -> Quality.FHD
            "UHD" -> Quality.UHD
            else -> Quality.HD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .build()
        val capture = VideoCapture.withOutput(recorder)
        val previous = videoCapture
        withContext(Dispatchers.Main) {
            // 精确解绑:只 unbind 自己上一个 VideoCapture,streamer 的 Preview /
            // EncoderPreview 不受影响,所以预览不会黑屏、推流不会断。
            if (previous != null) {
                runCatching { provider.unbind(previous) }
            }
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                capture
            )
        }
        videoCapture = capture
        return capture
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

    /** Internal helper(实现注释 plan §6 配套缩略图)。 */
    private fun emitState(update: (RecordingState) -> RecordingState) {
        _state.update(update)
    }

    companion object {
        /** 录像启动最低剩余磁盘字节数 — 默认 200MB,够录约 6 分钟 1080p。可被 [RecordingProfile.minFreeMb] 覆盖。 */
        const val MIN_FREE_BYTES: Long = 200L * 1024 * 1024
        /** 守护协程扫描间隔 — 30 秒粒度,切片误差 ±30s 可接受。 */
        const val GUARD_TICK_MS: Long = 30L * 1000
    }
}

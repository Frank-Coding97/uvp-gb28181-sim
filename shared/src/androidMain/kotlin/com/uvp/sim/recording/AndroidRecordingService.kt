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
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AndroidRecordingService — CameraX VideoCapture<Recorder> 实现。
 *
 * 关键点(plan §4 / §5):
 *   - 文件路径:`<filesDir>/recordings/<deviceId>/<YYYYMMDD>/<HHmmss>.mp4`
 *   - index.json:`<filesDir>/recordings/index.json`,挂在 deviceId 上
 *   - 30 分钟 timer 切片(后续 R04+:目前只做手动起停,timer 留扩展)
 *   - 录像与实时推流互斥(plan §5),由 SimulatorEngine 在 handleInvite 把住
 *
 * 异常处理:
 *   - bindToLifecycle 失败 → 状态进 Failed,UI toast
 *   - Recorder 抛异常 → 通过 VideoRecordEvent.Finalize.hasError 反馈
 *
 * 缩略图抽取:finalize 后异步走 [ThumbnailExtractor],失败不阻塞。
 */
class AndroidRecordingService(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val executor: Executor,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : RecordingService {

    private val mutex = Mutex()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _files = MutableStateFlow<List<RecordingFile>>(emptyList())
    override val files: StateFlow<List<RecordingFile>> = _files.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var activeOutputFile: File? = null
    private var activeChannelId: String? = null
    private var activeStartMs: Long = 0L
    private var activeSource: RecordSource = RecordSource.Manual
    private var thumbJob: Job? = null

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
        Unit
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
        return runCatching {
            ensureCameraBound()
            val now = clock.now()
            val outputFile = newOutputFile(now)
            outputFile.parentFile?.mkdirs()
            val recorder = videoCapture?.output ?: error("VideoCapture 未就绪")
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
            _state.value = RecordingState.Recording(
                startMs = activeStartMs,
                segmentIndex = 0,
                source = source
            )
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
            recording.stop()
            null  // 实际 finalize 在 handleRecordEvent 里
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
                val outputFile = activeOutputFile
                val started = activeStartMs
                val channel = activeChannelId
                val source = activeSource
                activeRecording = null
                activeOutputFile = null
                if (event.hasError() || outputFile == null || channel == null || started == 0L) {
                    val reason = event.cause?.message ?: "finalize error code=${event.error}"
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
                            _state.value = RecordingState.Idle
                        }
                        SystemLogger.emit(
                            LogLevel.Info, LogTag.Media,
                            "停止录像 → 文件=${outputFile.name} 时长=${durationMs}ms 大小=${sizeBytes}B"
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
                    }
                }
            }
        }
    }

    private suspend fun ensureCameraBound() {
        if (videoCapture != null) return
        val p = awaitCameraProvider(context)
        provider = p
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        val capture = VideoCapture.withOutput(recorder)
        withContext(Dispatchers.Main) {
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
        }
        videoCapture = capture
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

    private suspend fun awaitCameraProvider(ctx: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    try { cont.resume(future.get()) }
                    catch (e: Throwable) { cont.resumeWithException(e) }
                },
                Runnable::run
            )
        }

    /** Internal helper(实现注释 plan §6 配套缩略图)。 */
    private fun emitState(update: (RecordingState) -> RecordingState) {
        _state.update(update)
    }
}

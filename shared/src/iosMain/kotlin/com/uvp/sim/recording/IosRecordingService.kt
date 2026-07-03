package com.uvp.sim.recording

import com.uvp.sim.api.LogTag
import com.uvp.sim.app.RecordingEncoderConfig
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.RecordingProfile
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.snapshot.SnapshotCapture
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS recording service (v1.1 T-recording).
 *
 * ## Status
 *
 * v1.1 goal: full RecordingService interface + AVAssetWriter skeleton, with a
 * clean seam for v1.2 to attach real frame input.
 *
 * - state / files StateFlow + Idle/Recording/Stopping/Failed
 * - Path spec: <Documents>/recordings/<deviceId>/<YYYYMMDD>/<HHmmss>.mp4
 * - load / delete via NSFileManager
 * - 30-min segment guard (reads profileSupplier live)
 * - AVAssetWriter + video/audio input build & teardown
 * - NOT wired: IosCameraStreamer.stream() -> CMSampleBuffer feed.
 *   v1.2 T-record-feed will attach; until then mp4 has valid header but no
 *   samples.
 *
 * ## Input source problem
 *
 * iOS AVCaptureSession single-instance constraint: back camera can only have
 * one active session. IosCameraStreamer owns it (AVCaptureVideoDataOutput +
 * VTCompressionSession -> RTP). Recording cannot open a second session.
 *
 * Plan A (v1.2 target) - reuse the already-encoded H264Frame from
 * IosCameraStreamer, convert to CMSampleBufferRef and feed
 * AVAssetWriterInput.appendSampleBuffer. Complexity: Annex-B -> AVCC
 * conversion + CMSampleBufferCreate cinterop.
 *
 * Plan B (rejected) - attach a second AVCaptureVideoDataOutput inside writer.
 * Apple does not recommend two videoDataOutputs on the same session.
 *
 * ## v1.2 hook points
 *
 * 1. feedVideoFrame / feedAudioFrame reserved with H264Frame-aligned
 *    signatures.
 * 2. AppEngine wire-up (v1.2): after assembling IosRecordingService, forward
 *    IosCameraStreamer.stream() into feed methods.
 * 3. CMSampleBuffer construction lives inside feed methods (v1.2 TODO).
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosRecordingService(
    private val scope: CoroutineScope,
    private val deviceIdSupplier: () -> String,
    private val encoderConfigSupplier: () -> RecordingEncoderConfig,
    @Suppress("unused") private val osdConfigSupplier: () -> StateFlow<OsdConfig>,
    private val profileSupplier: () -> RecordingProfile,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val thumbnail: IosRecordingThumbnail = IosRecordingThumbnail(
        object : RecordingThumbnailSource {
            override suspend fun takeJpeg(): ByteArray? = SnapshotCapture().takeJpeg()
        },
    ),
) : RecordingService, IosVideoFrameSink {

    private val mutex = Mutex()
    private val sampleBufferBuilder = CMSampleBufferBuilder()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _files = MutableStateFlow<List<RecordingFile>>(emptyList())
    override val files: StateFlow<List<RecordingFile>> = _files.asStateFlow()

    /**
     * 2026-07-03 真机 race guard:
     * feedVideoFrame 跑在 camera dispatch queue,stop/finalizeWriterLocked 跑在 coroutine。
     * 之间没有 mutex,`_state` 检查跟 `input.appendSampleBuffer` 之间存在窗口 —— 若 stop
     * 抢先把 videoInput markAsFinished,capture queue 的 append 会打进 finished input,
     * 引发 AVAssetWriter 内部 async completion 挂死 -> stop() 的 finalize poll 卡住。
     * 这个 flag 在 stop() 首帧关闭前置为 true,feedVideoFrame 立即弃帧。
     */
    @kotlin.concurrent.Volatile
    private var inputsClosed: Boolean = false

    // ---- Live session refs (guarded by mutex) ----
    private var assetWriter: AVAssetWriter? = null
    private var videoInput: AVAssetWriterInput? = null
    private var audioInput: AVAssetWriterInput? = null
    private var activeOutputPath: String? = null
    private var activeChannelId: String? = null
    private var activeStartMs: Long = 0L
    private var activeSegmentIndex: Int = 0
    private var activeSource: RecordSource = RecordSource.Manual
    private var guardJob: Job? = null

    @kotlin.concurrent.Volatile
    private var pendingSegmentSplit: Boolean = false

    @kotlin.concurrent.Volatile
    private var finalizeDone: Boolean = false

    // ---- Filesystem paths ----
    private val documentsDir: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        )
        (paths.firstOrNull() as? String) ?: "/tmp"
    }

    private val baseDir: String by lazy {
        val p = (documentsDir as NSString).stringByAppendingPathComponent("recordings")
        NSFileManager.defaultManager.ensureDir(p)
        p
    }

    private val indexFilePath: String by lazy {
        (baseDir as NSString).stringByAppendingPathComponent("index.json")
    }

    // =========================================================
    // RecordingService overrides
    // =========================================================

    override suspend fun load(): Unit = mutex.withLock {
        runCatching {
            val fm = NSFileManager.defaultManager
            if (fm.fileExistsAtPath(indexFilePath)) {
                val text = withContext(Dispatchers.Default) {
                    NSString.stringWithContentsOfFile(
                        path = indexFilePath,
                        encoding = NSUTF8StringEncoding,
                        error = null,
                    ) as? String
                } ?: ""
                _files.value = RecordingIndex.decode(text).files
            } else {
                _files.value = emptyList()
            }
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_RECORDING_LOAD_FAIL msg=${it.message}",
            )
        }
        Unit
    }

    override suspend fun start(source: RecordSource, channelId: String): Result<Unit> =
        mutex.withLock {
            if (_state.value is RecordingState.Recording) {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "IOS_RECORDING_START_IGNORED already recording source=$source",
                )
                return@withLock Result.success(Unit)
            }
            // 重开 feed 通路(上一次 stop 会关它)。
            inputsClosed = false
            runCatching {
                val now = clock.now()
                val outputPath = newOutputPath(now)
                ensureParentDir(outputPath)

                val encCfg = encoderConfigSupplier()
                openWriter(outputPath, encCfg)

                activeOutputPath = outputPath
                activeChannelId = channelId
                activeStartMs = now.toEpochMilliseconds()
                activeSource = source
                if (!pendingSegmentSplit) activeSegmentIndex = 0
                pendingSegmentSplit = false

                _state.value = RecordingState.Recording(
                    startMs = activeStartMs,
                    segmentIndex = activeSegmentIndex,
                    source = source,
                )
                guardJob?.cancel()
                guardJob = scope.launch { runGuard() }

                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "IOS_RECORDING_START source=$source channel=$channelId path=$outputPath " +
                        "size=${encCfg.widthPx}x${encCfg.heightPx}@${encCfg.frameRate} " +
                        "br=${encCfg.bitrateBps}",
                )
            }.recoverCatching {
                _state.value = RecordingState.Failed(it.message ?: "unknown")
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_RECORDING_START_FAIL msg=${it.message}",
                )
                throw it
            }
        }

    override suspend fun stop(): Result<RecordingFile?> = mutex.withLock {
        val writer = assetWriter ?: return@withLock Result.success(null)
        // 先关闭 feed 通路,再切 state,阻止 capture queue 抢在 markAsFinished 之前 append。
        inputsClosed = true
        val previous = _state.value as? RecordingState.Recording
        if (previous != null) {
            _state.value = RecordingState.Stopping(previous, reason = "user_stop")
        }
        pendingSegmentSplit = false
        guardJob?.cancel()
        guardJob = null
        finalizeWriterLocked(writer)
    }

    override suspend fun delete(id: String): Result<Unit> = mutex.withLock {
        runCatching {
            val target = _files.value.firstOrNull { it.id == id } ?: return@runCatching
            withContext(Dispatchers.Default) {
                runCatching {
                    NSFileManager.defaultManager.removeItemAtPath(target.filePath, error = null)
                }
                target.thumbnailPath?.let {
                    runCatching {
                        NSFileManager.defaultManager.removeItemAtPath(it, error = null)
                    }
                }
            }
            val updated = RecordingIndex.remove(RecordingIndexFile(files = _files.value), id)
            _files.value = updated.files
            persistIndex(updated)
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_RECORDING_DELETE id=$id")
        }
    }

    // =========================================================
    // v1.2 feed hooks (currently no-op, AppEngine will wire later)
    // =========================================================

    /**
     * v1.2 hook: convert an encoded H.264 NAL frame into a CMSampleBuffer and
     * append to [videoInput]. Kept as stable public signature so AppEngine
     * wire-up doesn't reach into internals.
     */
    override fun feedVideoFrame(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) {
        if (inputsClosed) return
        val writer = assetWriter ?: return
        if (_state.value !is RecordingState.Recording) return

        // 收 SPS/PPS(每个 keyframe 都刷)。非 keyframe 且 SPS/PPS 未就位 → 丢帧。
        if (isKeyFrame) {
            sampleBufferBuilder.observeParameterSets(nalUnits)
        } else if (!sampleBufferBuilder.hasFormatDescriptionInputs()) {
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_VIDEO_DROP reason=missing_format ptsUs=$ptsUs",
            )
            return
        }

        // 懒启动:第一个带 SPS/PPS 的 keyframe 触发 phase2。videoInput 尚 null 时只接受 keyframe。
        val input = videoInput ?: run {
            if (!isKeyFrame) {
                SystemLogger.emit(
                    LogLevel.Debug, LogTag.Media,
                    "IOS_RECORDING_VIDEO_DROP reason=awaiting_first_keyframe ptsUs=$ptsUs",
                )
                return
            }
            if (!startWriterWithVideo(firstKeyPtsUs = ptsUs)) return
            videoInput ?: return
        }

        if (!input.isReadyForMoreMediaData()) {
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_VIDEO_DROP reason=input_busy ptsUs=$ptsUs",
            )
            return
        }

        val sample = sampleBufferBuilder.buildVideoSampleBuffer(
            nalUnits = nalUnits,
            ptsUs = ptsUs,
            durationUs = 1_000_000L / encoderConfigSupplier().frameRate.coerceAtLeast(1),
        ) ?: run {
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_VIDEO_DROP reason=sample_build_failed ptsUs=$ptsUs",
            )
            return
        }

        try {
            val appended = input.appendSampleBuffer(sample)
            if (!appended) {
                SystemLogger.emit(
                    LogLevel.Warning,
                    LogTag.Media,
                    "IOS_RECORDING_VIDEO_APPEND_FAIL status=${writer.status} " +
                        "msg=${writer.error?.localizedDescription ?: "unknown"}",
                )
            }
        } finally {
            CFRelease(sample)
        }
    }

    /**
     * v1.2 hook: convert one G.711 / AAC sample to CMSampleBuffer and append
     * to [audioInput].
     */
    @Suppress("UNUSED_PARAMETER")
    fun feedAudioFrame(payload: ByteArray, ptsUs: Long) {
        // TODO(v1.2 T-record-feed): build CMSampleBuffer + append.
    }

    // =========================================================
    // Internals: writer lifecycle
    // =========================================================

    /**
     * openWriter 阶段 1(2026-07-03 真机验重构):**只建 AVAssetWriter 骨架**。
     *
     * AVAssetWriterInput 的 passthrough 模式(outputSettings=null)必须提供
     * sourceFormatHint (CMVideoFormatDescription),否则 canAddInput 直接拒绝
     * (真机 iOS 严格,Simulator 部分行为不同,昨晚 overnight 单测漏过)。
     *
     * 我们的 H.264 sample 编码前拿不到 SPS/PPS,构不出 formatDescription。所以:
     *   - 起录时只建 writer(此时 writer.status = Unknown = 0),不 addInput,不 startWriting
     *   - 等到 feedVideoFrame 收到**第一个 keyframe**(SPS/PPS 已缓存):
     *       phase 2:build formatDescription → 用 sourceFormatHint 建 vIn →
     *       addInput → startWriting → startSessionAtSourceTime → append
     *
     * Audio 走 T-A2 后续修,暂不 addInput audio(mp4 只 video track,能过 ffprobe)。
     */
    private fun openWriter(outputPath: String, encCfg: RecordingEncoderConfig) {
        val outputUrl = NSURL(fileURLWithPath = outputPath)
        val writer = memScoped {
            val errPtr = alloc<ObjCObjectVar<NSError?>>()
            val fileType = AVFileTypeMPEG4
                ?: throw IllegalStateException("AVFileTypeMPEG4 constant null - SDK mismatch")
            val w = AVAssetWriter(uRL = outputUrl, fileType = fileType, error = errPtr.ptr)
            val err = errPtr.value
            if (err != null) {
                throw IllegalStateException(
                    "AVAssetWriter init failed: ${err.localizedDescription}",
                )
            }
            w
        }

        sampleBufferBuilder.reset()

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_RECORDING_OPEN_WRITER_PHASE1 status=${writer.status.toLong()} waiting for first keyframe"
        )

        assetWriter = writer
        // videoInput/audioInput 都保留 null — 等 feedVideoFrame 第一个 keyframe 触发 phase2。
        videoInput = null
        audioInput = null
    }

    /**
     * openWriter 阶段 2:第一个 keyframe 到达 + SPS/PPS 已缓存后调用。
     * 返回 true 表示 writer 已经 Writing,可以 append sample buffer。
     */
    private fun startWriterWithVideo(firstKeyPtsUs: Long): Boolean {
        val writer = assetWriter ?: return false
        if (videoInput != null) return true // 已经启动过

        val formatDescription = sampleBufferBuilder.buildFormatDescriptionOrNull() ?: run {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_RECORDING_FMT_DESC_NULL sps/pps unavailable",
            )
            return false
        }

        val vIn = AVAssetWriterInput(
            mediaType = AVMediaTypeVideo,
            outputSettings = null,
            sourceFormatHint = formatDescription,
        )
        vIn.expectsMediaDataInRealTime = true

        val canAdd = writer.canAddInput(vIn)
        if (!canAdd) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_RECORDING_ADD_VIDEO_INPUT_REJECTED_PHASE2 " +
                    "(sourceFormatHint provided but still rejected)",
            )
            CFRelease(formatDescription)
            return false
        }
        writer.addInput(vIn)

        val started = writer.startWriting()
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_RECORDING_OPEN_WRITER_PHASE2 startWriting=$started status=${writer.status.toLong()} " +
                "firstKeyPtsUs=$firstKeyPtsUs err=${writer.error?.localizedDescription ?: "none"}"
        )
        if (!started) {
            CFRelease(formatDescription)
            return false
        }
        // 第一个 sample 的 PTS 作为 session 起始,后续 sample PTS 都 >= 这个值。
        writer.startSessionAtSourceTime(CMTimeMake(value = firstKeyPtsUs, timescale = 1_000_000))

        CFRelease(formatDescription)
        videoInput = vIn
        return true
    }

    /**
     * finish writing + register RecordingFile in index. Called while holding
     * mutex. Reconfigures state to Idle (or schedules a segment rollover start
     * if pendingSegmentSplit).
     */
    private suspend fun finalizeWriterLocked(writer: AVAssetWriter): Result<RecordingFile?> {
        val outputPath = activeOutputPath
        val channel = activeChannelId
        val started = activeStartMs
        val source = activeSource
        val isSplit = pendingSegmentSplit

        val writerStatusRaw = writer.status.toLong()
        val didWriteAny = writerStatusRaw == 1L  // AVAssetWriterStatusWriting
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_RECORDING_FINALIZE_ENTRY status=$writerStatusRaw didWriteAny=$didWriteAny " +
                "writerErr=${writer.error?.localizedDescription ?: "none"}"
        )

        if (didWriteAny) {
            // writer 已 Writing (真的有 sample 灌进去) — 走 finishWriting 出有效 MP4。
            // 只对**已 addInput** 的 input 调 markAsFinished (videoInput 现在 lazy add,
            // audio 从不 addInput,不 mark)。
            videoInput?.let { vi ->
                runCatching { vi.markAsFinished() }.onFailure {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "IOS_RECORDING_MARK_VIDEO_FINISHED_FAIL msg=${it.message}",
                    )
                }
            }

            finalizeDone = false
            writer.finishWritingWithCompletionHandler {
                finalizeDone = true
            }
            withContext(Dispatchers.Default) {
                var waited = 0
                while (!finalizeDone && waited < 30) {
                    delay(100)
                    waited += 1
                }
            }
        } else {
            // Writer 从没 startWriting(第一个 keyframe 还没到 / phase2 失败) — cancelWriting。
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_RECORDING_STOP_EMPTY status=$writerStatusRaw outputPath=$outputPath",
            )
            runCatching { writer.cancelWriting() }.onFailure {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "IOS_RECORDING_CANCEL_FAIL msg=${it.message}",
                )
            }
        }

        assetWriter = null
        videoInput = null
        audioInput = null
        activeOutputPath = null

        if (outputPath == null || channel == null || started == 0L) {
            _state.value = RecordingState.Failed("finalize: missing session state")
            return Result.failure(IllegalStateException("finalize missing state"))
        }

        if (!didWriteAny) {
            // 空 recording — 不入索引不生成缩略图,直接把 state 回 Idle 返回 null。
            // 未落盘的 mp4 空壳文件由 iOS 沙箱自然回收;若已生成也删掉。
            runCatching { NSFileManager.defaultManager.removeItemAtPath(outputPath, error = null) }
            _state.value = RecordingState.Failed("no samples captured — camera stream not active")
            return Result.success(null)
        }

        val endMs = clock.now().toEpochMilliseconds()
        val durationMs = endMs - started
        val fileSize = fileSizeBytes(outputPath)

        val recordingId = NSUUID().UUIDString
        val thumbnailPath = thumbnail.captureForRecording(outputPath, recordingId)

        val recordingFile = RecordingFile(
            id = recordingId,
            startTimeMs = started,
            endTimeMs = endMs,
            durationMs = durationMs,
            channelId = channel,
            filePath = outputPath,
            sizeBytes = fileSize,
            thumbnailPath = thumbnailPath,
            source = source,
            type = RecordType.Time,
            secrecy = 0,
        )

        val currentList = _files.value
        val updated = RecordingIndexFile(files = currentList + recordingFile)
        _files.value = updated.files
        persistIndex(updated)

        if (!isSplit) {
            _state.value = RecordingState.Idle
        }

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            if (isSplit) "IOS_RECORDING_SEGMENT_ROLLOVER path=$outputPath dur=${durationMs}ms"
            else "IOS_RECORDING_STOP path=$outputPath dur=${durationMs}ms size=${fileSize}B",
        )

        if (isSplit) {
            activeSegmentIndex += 1
            // Can't restart inside mutex - schedule a new coroutine.
            scope.launch {
                runCatching { start(source, channel) }
                    .onFailure {
                        SystemLogger.emit(
                            LogLevel.Error, LogTag.Media,
                            "IOS_RECORDING_ROLLOVER_START_FAIL msg=${it.message}",
                        )
                    }
            }
        }

        return Result.success(recordingFile)
    }

    // =========================================================
    // Internals: guard loop
    // =========================================================

    private suspend fun runGuard() {
        val profile = profileSupplier()
        val segmentMs = profile.segmentMinutes.toLong() * 60 * 1000
        try {
            while (true) {
                delay(GUARD_TICK_MS)
                if (assetWriter == null) return
                val nowMs = clock.now().toEpochMilliseconds()
                val recordedMs = nowMs - activeStartMs
                if (segmentMs > 0 && recordedMs >= segmentMs) {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "IOS_RECORDING_SEGMENT_HIT minutes=${profile.segmentMinutes}",
                    )
                    pendingSegmentSplit = true
                    val writer = assetWriter ?: return
                    val previous = _state.value as? RecordingState.Recording
                    mutex.withLock {
                        if (previous != null) {
                            _state.value = RecordingState.Stopping(previous, reason = "segment_split")
                        }
                        finalizeWriterLocked(writer)
                    }
                    return
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // stop path - nothing to do
        }
    }

    // =========================================================
    // Internals: paths + fs helpers
    // =========================================================

    private fun newOutputPath(instant: Instant): String {
        val ldt = instant.toLocalDateTime(timeZone)
        val ymd = pad4(ldt.year) + pad2(ldt.monthNumber) + pad2(ldt.dayOfMonth)
        val hms = pad2(ldt.hour) + pad2(ldt.minute) + pad2(ldt.second)
        val deviceDir = (baseDir as NSString).stringByAppendingPathComponent(deviceIdSupplier())
        val dayDir = (deviceDir as NSString).stringByAppendingPathComponent(ymd)
        return (dayDir as NSString).stringByAppendingPathComponent("$hms.mp4")
    }

    private fun ensureParentDir(filePath: String) {
        val parent = deletingLastPathComponent(filePath)
        NSFileManager.defaultManager.ensureDir(parent)
    }

    private fun fileSizeBytes(path: String): Long {
        val fm = NSFileManager.defaultManager
        val attrs = fm.attributesOfItemAtPath(path, error = null) ?: return 0L
        val v = attrs["NSFileSize"] as? NSNumber
        return v?.longLongValue ?: 0L
    }

    private fun persistIndex(idx: RecordingIndexFile) {
        runCatching {
            val json = RecordingIndex.encode(idx)
            (json as NSString).writeToFile(
                path = indexFilePath,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null,
            )
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_RECORDING_INDEX_PERSIST_FAIL msg=${it.message}",
            )
        }
    }

    private companion object {
        const val GUARD_TICK_MS: Long = 30L * 1000
    }
}

/**
 * Manual "chop off last path component" - NSString's built-in property is a
 * Kotlin-restricted keyword collision, so we do it by hand: find last "/" and
 * return everything before it.
 */
private fun deletingLastPathComponent(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx <= 0) path else path.substring(0, idx)
}

private fun pad2(v: Int): String = v.toString().padStart(2, '0')

private fun pad4(v: Int): String = v.toString().padStart(4, '0')

/**
 * Create directory recursively if absent. Swallows errors — caller fails
 * downstream at the writer-open step if the dir truly can't be created.
 */
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

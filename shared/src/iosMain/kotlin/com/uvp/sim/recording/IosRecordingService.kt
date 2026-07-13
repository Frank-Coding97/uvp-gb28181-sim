package com.uvp.sim.recording

import com.uvp.sim.api.LogTag
import com.uvp.sim.app.RecordingEncoderConfig
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.camera.EncodingHandle
import com.uvp.sim.camera.IosCameraController
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.RecordingProfile
import com.uvp.sim.media.NalType
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
import kotlin.time.Clock
import kotlin.time.Instant
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
) : RecordingService, IosVideoFrameSink, IosAudioFrameSink {

    private val mutex = Mutex()
    private val sampleBufferBuilder = CMSampleBufferBuilder()

    /**
     * cross-review R1 #5 拆分 step 1:所有 video/audio 诊断计数 + baseline PTS 抽到
     * [RecordingDiagnostics]。字段直接暴露(internal 可见性),外面通过 [diag] 访问,
     * 不引入 getter/setter 转发的复杂度。
     */
    private val diag = RecordingDiagnostics()

    /**
     * cross-review R1 #5 拆分 step 2:文件路径 / index 持久化 / newOutputPath 抽到
     * [RecordingFileStore]。
     */
    private val fileStore = RecordingFileStore(deviceIdSupplier, timeZone)

    /**
     * cross-review R1 #5 拆分 step 3:AVAssetWriter phase1/phase2 + finalize + audio input build
     * 抽到 [RecordingWriterSession]。
     */
    private val writer = RecordingWriterSession(sampleBufferBuilder)

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _files = MutableStateFlow<List<RecordingFile>>(emptyList())
    override val files: StateFlow<List<RecordingFile>> = _files.asStateFlow()

    /**
     * 2026-07-03 真机 race guard:
     * feedVideoFrame 跑在 camera dispatch queue,stop/finalizeWriterLocked 跑在 coroutine。
     * 之间没有 mutex,`_state` 检查跟 `input.appendSampleBuffer` 之间存在窗口 —— 若 stop
     * 抢先把 writer.videoInput markAsFinished,capture queue 的 append 会打进 finished input,
     * 引发 AVAssetWriter 内部 async completion 挂死 -> stop() 的 finalize poll 卡住。
     * 这个 flag 在 stop() 首帧关闭前置为 true,feedVideoFrame 立即弃帧。
     */
    @kotlin.concurrent.Volatile
    private var inputsClosed: Boolean = false

    // ---- Live session refs (guarded by mutex) ----
    // writer.assetWriter / writer.videoInput / writer.audioInput 移到 [writer]
    private var activeOutputPath: String? = null
    private var activeChannelId: String? = null
    private var activeStartMs: Long = 0L
    private var activeSegmentIndex: Int = 0
    private var activeSource: RecordSource = RecordSource.Manual
    private var guardJob: Job? = null

    @kotlin.concurrent.Volatile
    private var pendingSegmentSplit: Boolean = false

    /**
     * T-P5-1:录像触发 encoding 的引用计数句柄。start 时 requestEncoding,stop 时 close。
     * rollover 场景(pendingSegmentSplit=true)finalize 后不 close(保 VT session 常驻,
     * 避免下段 B 起手 300ms 冷启动无帧)。start() 内幂等:handle 非 null 跳过 requestEncoding。
     */
    @kotlin.concurrent.Volatile
    private var encodingHandle: EncodingHandle? = null

    // =========================================================
    // RecordingService overrides
    // =========================================================

    override suspend fun load(): Unit = mutex.withLock {
        _files.value = fileStore.loadIndex()
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
                val outputPath = fileStore.newOutputPath(now)
                fileStore.ensureParentDir(outputPath)

                val encCfg = encoderConfigSupplier()
                openWriter(outputPath, encCfg)
                // T-B3-4:起录时 snapshot 当前音频 codec,整个录像 session 内不变(spec Q4)。
                diag.activeAudioCodec = encCfg.audioCodec
                // 2026-07-13 HEVC:同款 snapshot video codec,feedVideoFrame IDR 判据 +
                // sampleBufferBuilder 都靠这个字段做 H.264/H.265 分派。
                diag.activeVideoCodec = encCfg.videoCodec
                diag.reset()

                // T-P5-1:向 controller 请 encoding。rollover 场景 handle 已有,跳过避免 refCount 漂移。
                if (encodingHandle == null) {
                    // 用 recording encoder config 派生 CaptureConfig 让 controller 拿得到 dim/fps
                    val captureCfg = CaptureConfig(
                        widthPx = encCfg.widthPx,
                        heightPx = encCfg.heightPx,
                        frameRate = encCfg.frameRate,
                        bitrateBps = encCfg.bitrateBps,
                    )
                    IosCameraController.stashConfigForEncoding(captureCfg)
                    encodingHandle = IosCameraController.requestEncoding()
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "IOS_RECORDING_ENCODING_HANDLE_ACQUIRED source=$source"
                    )
                }
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

                // 主动请求下一帧 IDR。必须在 state 已进入 Recording 之后再请求,
                // 否则真机上 VT 很快回调时 feedVideoFrame 会因 state 尚未切换而丢掉首个 IDR,
                // 短录像 stop 时 writer 仍未 startWriting,最终不入 index。
                IosCameraController.requestKeyFrame()
                guardJob?.cancel()
                guardJob = scope.launch { runGuard() }

                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "IOS_RECORDING_START source=$source channel=$channelId path=$outputPath " +
                        "size=${encCfg.widthPx}x${encCfg.heightPx}@${encCfg.frameRate} " +
                        "br=${encCfg.bitrateBps} encodingActive=${IosCameraController.encodingActive.value}",
                )
            }.recoverCatching {
                _state.value = RecordingState.Failed(it.message ?: "unknown")
                // T-P5-1:失败路径 close handle 避免泄漏
                encodingHandle?.close()
                encodingHandle = null
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_RECORDING_START_FAIL msg=${it.message}",
                )
                throw it
            }
        }

    override suspend fun stop(): Result<RecordingFile?> = mutex.withLock {
        if (writer.assetWriter == null) return@withLock Result.success(null)
        // 先关闭 feed 通路,再切 state,阻止 capture queue 抢在 markAsFinished 之前 append。
        inputsClosed = true
        val previous = _state.value as? RecordingState.Recording
        if (previous != null) {
            _state.value = RecordingState.Stopping(previous, reason = "user_stop")
        }
        pendingSegmentSplit = false
        guardJob?.cancel()
        guardJob = null
        // T-P5-1:真 stop 时 close handle(不是 rollover)。rollover 走 runGuard 分支,那里不 close。
        encodingHandle?.close()
        encodingHandle = null
        finalizeWriterLocked()
    }

    override suspend fun delete(id: String): Result<Unit> = mutex.withLock {
        runCatching {
            val target = _files.value.firstOrNull { it.id == id } ?: return@runCatching
            fileStore.deleteFile(target.filePath)
            target.thumbnailPath?.let { fileStore.deleteFile(it) }
            val updated = RecordingIndex.remove(RecordingIndexFile(files = _files.value), id)
            _files.value = updated.files
            fileStore.persistIndex(updated)
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_RECORDING_DELETE id=$id")
        }
    }

    // =========================================================
    // v1.2 feed hooks (currently no-op, AppEngine will wire later)
    // =========================================================

    /**
     * v1.2 hook: convert an encoded H.264 NAL frame into a CMSampleBuffer and
     * append to [writer.videoInput]. Kept as stable public signature so AppEngine
     * wire-up doesn't reach into internals.
     */
    override fun feedVideoFrame(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) {
        if (inputsClosed) return
        val av = writer.assetWriter ?: return
        if (_state.value !is RecordingState.Recording) return
        diag.lastVideoFeedAtMs = clock.now().toEpochMilliseconds()
        diag.videoFramesSeen += 1
        if (diag.firstVideoPtsUs == -1L) {
            diag.firstVideoPtsUs = ptsUs
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_FIRST_VIDEO_FRAME ptsUs=$ptsUs isKey=$isKeyFrame nalCount=${nalUnits.size}",
            )
        }

        // 用真实 NAL type 判 IDR。`isKeyFrame` 参数在 EncodingSession 里永远为 true
        // (每帧都提取 SPS/PPS,不细分 IDR/non-IDR),不能作为"首帧可独立解码"的判据。
        //
        // 2026-07-13 HEVC:判据必须过 activeVideoCodec —— H.264 用 low 5-bit == 5,H.265 用
        // (byte>>1)&0x3F ∈ {19,20,21}。硬编 `& 0x1F == IDR` 在 H.265 下永远命中不到,导致
        // 一直卡在 dropAwaitingIdr,writer 死等 IDR 直到 stop。
        val activeVideoCodec = diag.activeVideoCodec
        val hasIdrSlice = nalUnits.any { nal ->
            if (nal.isEmpty()) return@any false
            activeVideoCodec.isKeyNal(activeVideoCodec.nalType(nal[0]))
        }

        // 每帧都 observe,函数内部会自行过滤只收参数集(H.264 SPS/PPS / H.265 VPS/SPS/PPS)。
        sampleBufferBuilder.observeParameterSets(nalUnits)
        if (!sampleBufferBuilder.hasFormatDescriptionInputs()) {
            diag.dropMissingFormat += 1
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_VIDEO_DROP reason=missing_format ptsUs=$ptsUs",
            )
            return
        }

        // AVAssetWriter 要求首帧必须是可独立解码的 IDR。VT encoding session 若被
        // 复用(录像 start 时 encoding 已在跑,如同时推流),接到的首帧可能是 P frame,
        // 触发 kCMSampleBufferError_DataFailed(-16364)。一律丢弃直到真 IDR 到来。
        if (diag.videoBaselinePtsUs == -1L && !hasIdrSlice) {
            diag.dropAwaitingIdr += 1
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_VIDEO_DROP reason=awaiting_first_idr ptsUs=$ptsUs",
            )
            return
        }
        val relPtsUs = when (val decision = RecordingPtsPolicy.classify(
            baselinePtsUs = diag.videoBaselinePtsUs,
            lastAppendedRelPtsUs = diag.lastAppendedRelPtsUs,
            rawPtsUs = ptsUs,
        )) {
            is RecordingPtsPolicy.Decision.FirstSample -> {
                diag.videoBaselinePtsUs = decision.baselinePtsUs
                SystemLogger.emit(
                    LogLevel.Info,
                    LogTag.Media,
                    "IOS_RECORDING_FIRST_IDR ptsUs=$ptsUs nalCount=${nalUnits.size}",
                )
                decision.relPtsUs
            }
            is RecordingPtsPolicy.Decision.Accept -> decision.relPtsUs
            is RecordingPtsPolicy.Decision.Negative -> {
                diag.dropPtsRegression += 1
                SystemLogger.emit(
                    LogLevel.Debug,
                    LogTag.Media,
                    "IOS_RECORDING_VIDEO_DROP reason=pts_regression rel=${decision.relPtsUs} " +
                        "baseline=${decision.baselinePtsUs} ptsUs=$ptsUs",
                )
                return
            }
            is RecordingPtsPolicy.Decision.NonMonotonic -> {
                diag.dropNonMonotonicPts += 1
                SystemLogger.emit(
                    LogLevel.Debug,
                    LogTag.Media,
                    "IOS_RECORDING_VIDEO_DROP reason=non_monotonic_pts rel=${decision.relPtsUs} " +
                        "last=${decision.lastAppendedRelPtsUs} rawPtsUs=$ptsUs",
                )
                return
            }
        }

        // 首帧必是 IDR(上面已 gate),phase2 用相对时间 0 起 session
        val input: AVAssetWriterInput = writer.videoInput ?: run {
            if (!startWriterWithVideo(firstKeyPtsUs = relPtsUs)) return
            SystemLogger.emit(
                LogLevel.Info,
                LogTag.Media,
                "IOS_RECORDING_WRITER_BECAME_ACTIVE relPtsUs=$relPtsUs baseline=${diag.videoBaselinePtsUs}",
            )
            writer.videoInput ?: return
        }

        if (!input.isReadyForMoreMediaData()) {
            diag.dropInputBusy += 1
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_VIDEO_DROP reason=input_busy ptsUs=$ptsUs",
            )
            return
        }

        val sample = sampleBufferBuilder.buildVideoSampleBuffer(
            nalUnits = nalUnits,
            ptsUs = relPtsUs,
            durationUs = 1_000_000L / encoderConfigSupplier().frameRate.coerceAtLeast(1),
        ) ?: run {
            diag.dropSampleBuildFailed += 1
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
                diag.appendFailures += 1
                val err = av.error
                val underlying = err?.userInfo?.get("NSUnderlyingError") as? platform.Foundation.NSError
                // 诊断 -16364(DataFailed):打出过滤前每个 NAL 的 (type, size),验证
                // SPS(7)/PPS(8) 是否真被 buildAvccPayload 过滤;打出第一个 slice NAL 前 8
                // 字节 hex,验证 slice NAL 数据结构。
                val nalTypes = nalUnits.joinToString(",") { nal ->
                    val t = if (nal.isEmpty()) -1 else (nal[0].toInt() and 0x1F)
                    "t${t}/${nal.size}b"
                }
                val firstSliceNal = nalUnits.firstOrNull { nal ->
                    nal.isNotEmpty() && (nal[0].toInt() and 0x1F).let {
                        it != NalType.SPS && it != NalType.PPS
                    }
                }
                val sliceHex = firstSliceNal
                    ?.take(8)
                    ?.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
                    ?: "none"
                SystemLogger.emit(
                    LogLevel.Warning,
                    LogTag.Media,
                    "IOS_RECORDING_VIDEO_APPEND_FAIL " +
                        "status=${av.status.toLong()} " +
                        "errDomain=${err?.domain ?: "nil"} " +
                        "errCode=${err?.code?.toLong() ?: 0L} " +
                        "underlyingDomain=${underlying?.domain ?: "nil"} " +
                        "underlyingCode=${underlying?.code?.toLong() ?: 0L} " +
                        "rawPtsUs=$ptsUs relPtsUs=$relPtsUs baseline=$diag.videoBaselinePtsUs " +
                        "isKey=$isKeyFrame nals=[$nalTypes] sliceHead=$sliceHex " +
                        "msg=${err?.localizedDescription ?: "unknown"}",
                )
            } else {
                diag.videoFramesAppended += 1
                diag.lastAppendedRelPtsUs = relPtsUs
                diag.lastVideoAppendAtMs = clock.now().toEpochMilliseconds()
            }
        } finally {
            CFRelease(sample)
        }
    }

    /**
     * T-B3-3:实装 feedAudioFrame。
     *
     * 步骤(plan §3.3):
     *   1. inputsClosed / writer.audioInput == null → return + drop 分类
     *   2. video baseline == -1(还没等 IDR)→ 丢弃(保 audio 从 IDR 附近开始)
     *   3. RecordingPtsPolicy 分类音频 PTS
     *   4. AAC path 剥 ADTS 头(前 7 字节)
     *   5. G.711A path payload 原样
     *   6. writer.audioInput.isReadyForMoreMediaData 检查
     *   7. writer.audioInput.appendSampleBuffer(sample)
     */
    override fun feedAudioFrame(
        payload: ByteArray,
        ptsUs: Long,
        codec: com.uvp.sim.media.AudioCodec,
    ) {
        if (inputsClosed) {
            diag.dropInputBusy++
            return
        }
        val input = writer.audioInput ?: return
        diag.audioFramesSeen++

        // audio 等 video baseline 建立后再 append(保 IDR 起点)
        val videoBase = diag.videoBaselinePtsUs
        if (videoBase < 0) {
            // 尚未收到首个 IDR,先丢弃(不算 regression)
            return
        }

        val classify = com.uvp.sim.recording.RecordingPtsPolicy.classify(
            baselinePtsUs = diag.audioBaselinePtsUs,
            lastAppendedRelPtsUs = diag.lastAppendedAudioRelPtsUs,
            rawPtsUs = ptsUs,
        )
        val relPts: Long = when (classify) {
            is com.uvp.sim.recording.RecordingPtsPolicy.Decision.FirstSample -> {
                diag.audioBaselinePtsUs = ptsUs
                0L
            }
            is com.uvp.sim.recording.RecordingPtsPolicy.Decision.Accept -> classify.relPtsUs
            is com.uvp.sim.recording.RecordingPtsPolicy.Decision.Negative,
            is com.uvp.sim.recording.RecordingPtsPolicy.Decision.NonMonotonic -> {
                diag.dropAudioPtsRegression++
                return
            }
        }

        // AAC 剥 ADTS 头(mp4 里不装 ADTS)
        val rawPayload = when (codec) {
            com.uvp.sim.media.AudioCodec.AAC -> {
                if (payload.size <= 7) {
                    diag.dropAudioPtsRegression++
                    return
                }
                payload.copyOfRange(7, payload.size)
            }
            else -> payload
        }

        val sampleBuf = writer.audioSampleBuilder.build(
            payload = rawPayload,
            relPtsUs = relPts,
            codec = codec,
        )
        if (sampleBuf == null) {
            diag.audioAppendFailures++
            return
        }
        if (!input.readyForMoreMediaData) {
            diag.dropInputBusy++
            writer.releaseSampleBuffer(sampleBuf)
            return
        }
        val ok = input.appendSampleBuffer(sampleBuf)
        if (!ok) {
            diag.audioAppendFailures++
        } else {
            diag.audioFramesAppended++
            diag.lastAppendedAudioRelPtsUs = relPts
        }
        writer.releaseSampleBuffer(sampleBuf)
    }

    /** 老签名保留兜底 —— G.711A 路径,codec 默认。iOS AppHost 不应直接调这个 overload。 */
    fun feedAudioFrame(payload: ByteArray, ptsUs: Long) {
        val codec = diag.activeAudioCodec ?: com.uvp.sim.media.AudioCodec.G711A
        feedAudioFrame(payload = payload, ptsUs = ptsUs, codec = codec)
    }

    // openAudioInput / audioSampleBuilder / releaseSampleBuffer 已抽到 RecordingWriterSession。

    // =========================================================
    // Internals: writer lifecycle
    // =========================================================

    /**
     * openWriter 阶段 1 委托到 [RecordingWriterSession.openWriter]。
     *
     * 阶段 2([startWriterWithVideo])需要读 [diag.activeAudioCodec],因此保留一个薄 wrapper
     * 让主服务 activeAudioCodec 语义留在这一层(writer session 不认识 diag)。
     */
    private fun openWriter(outputPath: String, encCfg: RecordingEncoderConfig) {
        writer.openWriter(outputPath, encCfg)
    }

    private fun startWriterWithVideo(firstKeyPtsUs: Long): Boolean {
        return writer.startWithFirstVideo(firstKeyPtsUs, diag.activeAudioCodec)
    }

    /**
     * finish writing + register RecordingFile in index. Called while holding
     * mutex. Reconfigures state to Idle (or schedules a segment rollover start
     * if pendingSegmentSplit).
     *
     * Writer 的 markAsFinished + finishWriting + await 委托到 [RecordingWriterSession.finalizeWriter],
     * 主服务负责 state / index / thumbnail 更新。
     */
    private suspend fun finalizeWriterLocked(): Result<RecordingFile?> {
        val outputPath = activeOutputPath
        val channel = activeChannelId
        val started = activeStartMs
        val source = activeSource
        val isSplit = pendingSegmentSplit

        val outcome = writer.finalizeWriter()
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_RECORDING_FINALIZE_ENTRY status=${outcome.writerStatusRaw} didWriteAny=${outcome.didWriteAny} " +
                "writerErr=${outcome.writerErrorMessage ?: "none"} " +
                "seen=${diag.videoFramesSeen} appended=${diag.videoFramesAppended} " +
                "dropFmt=${diag.dropMissingFormat} dropIdr=${diag.dropAwaitingIdr} dropPts=${diag.dropPtsRegression} " +
                "dropMono=${diag.dropNonMonotonicPts} " +
                "dropBusy=${diag.dropInputBusy} dropSample=${diag.dropSampleBuildFailed} appendFail=${diag.appendFailures} " +
                "firstPtsUs=${diag.firstVideoPtsUs} baseline=${diag.videoBaselinePtsUs} lastAppended=${diag.lastAppendedRelPtsUs}"
        )
        val didWriteAny = outcome.didWriteAny

        activeOutputPath = null
        diag.resetVideoBaseline()

        if (outputPath == null || channel == null || started == 0L) {
            _state.value = RecordingState.Failed("finalize: missing session state")
            return Result.failure(IllegalStateException("finalize missing state"))
        }

        if (!didWriteAny) {
            // 空 recording — 不入索引不生成缩略图,直接把 state 回 Idle 返回 null。
            // 未落盘的 mp4 空壳文件由 iOS 沙箱自然回收;若已生成也删掉。
            fileStore.deleteFile(outputPath)
            _state.value = RecordingState.Failed("no samples captured — camera stream not active")
            return Result.success(null)
        }

        val endMs = clock.now().toEpochMilliseconds()
        val durationMs = endMs - started
        val fileSize = fileStore.fileSizeBytes(outputPath)

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
        fileStore.persistIndex(updated)
        // 2026-07-13 现场诊断:老板报告"iOS 多次录像列表始终只显示一条"。
        // finalize 用的 currentList 若是 emptyList(冷启动漏调 load 的老包 / load 失败),
        // 之前的历史记录会被 persist 覆盖。这里 emit 每次 append 前后的 size + 前 3 条 id,
        // 让老板真机日志能直接看到"append 语义有没有被 reset 到 empty 起点"。
        val expectedSize = currentList.size + 1
        val actualSize = updated.files.size
        val logLevel = if (actualSize == expectedSize) LogLevel.Debug else LogLevel.Error
        val previewIds = updated.files.take(3).joinToString(",") { it.id.take(8) }
        SystemLogger.emit(
            logLevel, LogTag.Media,
            "IOS_RECORDING_INDEX_STATE before=${currentList.size} after=$actualSize " +
                "expected=$expectedSize appendedId=${recordingFile.id.take(8)} " +
                "previewIds=[$previewIds]",
        )
        SystemLogger.emit(
            LogLevel.Info,
            LogTag.Media,
            "IOS_RECORDING_INDEX_APPEND id=$recordingId path=$outputPath count=${updated.files.size} " +
                "thumb=${thumbnailPath != null}",
        )

        if (!isSplit) {
            _state.value = RecordingState.Idle
        }

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            if (isSplit) "IOS_RECORDING_SEGMENT_ROLLOVER path=$outputPath dur=${durationMs}ms"
            else "IOS_RECORDING_STOP path=$outputPath dur=${durationMs}ms size=${fileSize}B " +
                "indexedCount=${updated.files.size}",
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
                if (writer.assetWriter == null) return
                val nowMs = clock.now().toEpochMilliseconds()
                val recordedMs = nowMs - activeStartMs
                if (segmentMs > 0 && recordedMs >= segmentMs) {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "IOS_RECORDING_SEGMENT_HIT minutes=${profile.segmentMinutes}",
                    )
                    pendingSegmentSplit = true
                    val previous = _state.value as? RecordingState.Recording
                    mutex.withLock {
                        if (previous != null) {
                            _state.value = RecordingState.Stopping(previous, reason = "segment_split")
                        }
                        finalizeWriterLocked()
                    }
                    return
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // stop path - nothing to do
        }
    }

    // ---- 诊断 accessor(IosAppHost 心跳读)----

    fun lastVideoFeedAtMs(): Long = diag.lastVideoFeedAtMs
    fun lastVideoAppendAtMs(): Long = diag.lastVideoAppendAtMs

    // ---- T-B3-1:测试用诊断快照,暴露 audio 计数 + baseline 状态 ----

    internal data class AudioDiagnosticsSnapshot(
        val activeAudioCodec: com.uvp.sim.media.AudioCodec?,
        val audioBaselinePtsUs: Long,
        val lastAppendedAudioRelPtsUs: Long,
        val audioFramesSeen: Int,
        val audioFramesAppended: Int,
        val dropAudioPtsRegression: Int,
        val audioAppendFailures: Int,
        val audioInputPresent: Boolean,
    )

    /** iosTest 用来观察 audio 侧内部 state 的窗口,不动生产语义。 */
    internal fun snapshotAudioDiagnostics(): AudioDiagnosticsSnapshot {
        val s = diag.audioSnapshot()
        return AudioDiagnosticsSnapshot(
            activeAudioCodec = s.activeAudioCodec,
            audioBaselinePtsUs = s.audioBaselinePtsUs,
            lastAppendedAudioRelPtsUs = s.lastAppendedAudioRelPtsUs,
            audioFramesSeen = s.audioFramesSeen,
            audioFramesAppended = s.audioFramesAppended,
            dropAudioPtsRegression = s.dropAudioPtsRegression,
            audioAppendFailures = s.audioAppendFailures,
            audioInputPresent = writer.audioInput != null,
        )
    }

    private companion object {
        const val GUARD_TICK_MS: Long = 30L * 1000
    }
}


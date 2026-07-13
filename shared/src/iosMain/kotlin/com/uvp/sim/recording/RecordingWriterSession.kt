package com.uvp.sim.recording

import com.uvp.sim.api.LogTag
import com.uvp.sim.app.RecordingEncoderConfig
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSError
import platform.Foundation.NSURL

/**
 * cross-review R1 #5 拆分 step 3(from [IosRecordingService]):
 * AVAssetWriter phase 1(build)+ phase 2(add inputs + startWriting)+ finalize
 * (markAsFinished + finishWriting)。
 *
 * ## 生命周期
 *
 * 1. [openWriter]:phase 1,只建 AVAssetWriter 骨架,videoInput/audioInput 保持 null。
 *    真机 AVAssetWriterInput 的 passthrough 模式(outputSettings=null)必须提供
 *    sourceFormatHint(CMVideoFormatDescription),而我们要等首个 keyframe 才能构造。
 * 2. [startWithFirstVideo]:phase 2,SPS/PPS 已 observe → buildFormatDescription →
 *    addVideoInput → startWriting → addAudioInput(若 codec 非 null)。
 * 3. feed 阶段:主服务 read [videoInput] / [audioInput] 后调 appendSampleBuffer。
 * 4. [finalizeWriter]:markAsFinished + finishWriting + await 完成信号。
 *    返回 [FinalizeOutcome] 让主服务决定 index/state 分支。
 *
 * ## 状态
 *
 * 单 writer 对应单 recording session。start/stop 复用同一 session 实例即可
 * ([reset] 清零字段);主服务通常在 start() 新建 session、finalize 后丢弃。
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal class RecordingWriterSession(
    val sampleBufferBuilder: CMSampleBufferBuilder,
) {

    @Volatile
    var assetWriter: AVAssetWriter? = null
        private set

    @Volatile
    var videoInput: AVAssetWriterInput? = null
        private set

    @Volatile
    var audioInput: AVAssetWriterInput? = null
        private set

    @Volatile
    private var finalizeDone: Boolean = false

    /** audio sample buffer builder(feed 路径用,writer session 持有以共享 lifetime)。 */
    val audioSampleBuilder: CMAudioSampleBufferBuilder = CMAudioSampleBufferBuilder()

    /** phase 1:build AVAssetWriter,keeps inputs null。 */
    fun openWriter(outputPath: String, @Suppress("UNUSED_PARAMETER") encCfg: RecordingEncoderConfig) {
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
        videoInput = null
        audioInput = null
    }

    /**
     * phase 2:第一个 keyframe 到达 + SPS/PPS 已 observe 后调用。
     * 返回 true 表示 writer 已 Writing,可以 append sample buffer。
     */
    fun startWithFirstVideo(firstKeyPtsUs: Long, activeAudioCodec: com.uvp.sim.media.AudioCodec?): Boolean {
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
        writer.startSessionAtSourceTime(CMTimeMake(value = firstKeyPtsUs, timescale = 1_000_000))

        CFRelease(formatDescription)
        videoInput = vIn

        // T-B3-4:视频 input 就绪后同步开 audio input(若 activeAudioCodec 非空)。
        // 语义:writer.startWriting 之前必须把所有 input addInput 完;这里在
        // startWriting 之前 addInput,addInput 失败(canAddInput false)不阻塞
        // 起录 —— 只是这次录像没有音轨,和 v1.2 保持一致(不返回 false)。
        activeAudioCodec?.let { codec ->
            val aIn = openAudioInput(codec) ?: return@let
            if (writer.canAddInput(aIn)) {
                writer.addInput(aIn)
                audioInput = aIn
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "IOS_RECORDING_ADD_AUDIO_INPUT_PHASE2 codec=${codec.label}",
                )
            } else {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "IOS_RECORDING_ADD_AUDIO_INPUT_REJECTED codec=${codec.label}",
                )
            }
        }
        return true
    }

    /**
     * T-B3-2:按 codec 构造 AVAssetWriterInput(audio 侧)。
     * G.711A → kAudioFormatALaw / 8000 Hz / mono
     * AAC → kAudioFormatMPEG4AAC / 44100 Hz / mono
     *
     * 主服务 phase2 时通过本函数拿到 aIn,自己决定是否 addInput。
     */
    internal fun openAudioInput(codec: com.uvp.sim.media.AudioCodec): AVAssetWriterInput? {
        val (formatId, sampleRate, channels) = when (codec) {
            com.uvp.sim.media.AudioCodec.G711A -> Triple(
                platform.CoreAudioTypes.kAudioFormatALaw,
                8_000.0,
                1,
            )
            com.uvp.sim.media.AudioCodec.G711U -> Triple(
                platform.CoreAudioTypes.kAudioFormatULaw,
                8_000.0,
                1,
            )
            com.uvp.sim.media.AudioCodec.AAC -> Triple(
                platform.CoreAudioTypes.kAudioFormatMPEG4AAC,
                44_100.0,
                1,
            )
        }
        val settings: Map<Any?, Any?> = mapOf(
            AVFormatIDKey to formatId.toLong(),
            AVSampleRateKey to sampleRate,
            AVNumberOfChannelsKey to channels.toLong(),
        )
        val aIn = AVAssetWriterInput(mediaType = AVMediaTypeAudio, outputSettings = settings)
        aIn.expectsMediaDataInRealTime = true
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_RECORDING_OPEN_AUDIO_INPUT codec=${codec.label} sr=${sampleRate.toInt()} ch=$channels",
        )
        return aIn
    }

    /**
     * 走到 markAsFinished + finishWriting + poll finalizeDone 完成流程。
     * 返回 [FinalizeOutcome] 让主服务决定 index/state 分支。
     * 完成后 [assetWriter] / [videoInput] / [audioInput] 全部置 null。
     */
    suspend fun finalizeWriter(): FinalizeOutcome {
        val writer = assetWriter ?: return FinalizeOutcome(didWriteAny = false, writerStatusRaw = -1L, writerErrorMessage = null)
        val writerStatusRaw = writer.status.toLong()
        val didWriteAny = writerStatusRaw == 1L  // AVAssetWriterStatusWriting

        if (didWriteAny) {
            // Writer 已 Writing (真的有 sample 灌进去) — 走 finishWriting 出有效 MP4。
            // 只对**已 addInput** 的 input 调 markAsFinished (videoInput 现在 lazy add,
            // audio 只在 startWithFirstVideo 里 addInput,同样保 non-null 判定)。
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
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_RECORDING_STOP_EMPTY status=$writerStatusRaw reason=no_video_sample_appended",
            )
            runCatching { writer.cancelWriting() }.onFailure {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "IOS_RECORDING_CANCEL_FAIL msg=${it.message}",
                )
            }
        }

        val errMsg = writer.error?.localizedDescription
        assetWriter = null
        videoInput = null
        audioInput = null

        return FinalizeOutcome(
            didWriteAny = didWriteAny,
            writerStatusRaw = writerStatusRaw,
            writerErrorMessage = errMsg,
        )
    }

    /** 释放 CoreMedia object(runCatching 兜底 K/N 侧偶发 CFRelease 语义差)。 */
    fun releaseSampleBuffer(sample: CMSampleBufferRef?) {
        if (sample != null) {
            runCatching { CFRelease(sample) }
        }
    }

    data class FinalizeOutcome(
        /** writer.status == Writing;true = 有 sample 已 flush,false = cancelWriting */
        val didWriteAny: Boolean,
        /** writer.status 数值,便于 log */
        val writerStatusRaw: Long,
        /** finishWriting 完成时的 writer.error?.localizedDescription */
        val writerErrorMessage: String?,
    )
}

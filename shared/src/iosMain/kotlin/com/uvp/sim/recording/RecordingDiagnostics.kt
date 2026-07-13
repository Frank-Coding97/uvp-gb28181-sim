package com.uvp.sim.recording

import kotlin.concurrent.Volatile

/**
 * cross-review R1 #5 拆分 step 1(from [IosRecordingService]):
 * 录像 session 内的所有诊断计数 + baseline PTS + reset + snapshot。
 *
 * 都是 best-effort 单写者场景(feedVideoFrame / feedAudioFrame 在 camera queue 上跑,
 * finalizeWriterLocked 在 coroutine 里读),原生跟服务 class 保持 @Volatile 语义,
 * 不引入新的原子并发模型。
 *
 * 无外部依赖,一个录像 session 一个实例(录像结束 [reset] 清零复用)。
 */
internal class RecordingDiagnostics {

    // ---- Video 侧 ----

    /**
     * 视频 PTS 归零基准。VT 输出的 CMSampleBuffer PTS 来自 CoreMedia host time,
     * 有时是负数或极大值(实测 iOS 15+ 可能出 -1.6e9 微秒),AVAssetWriter 拒绝并
     * 报 kCMSampleBufferError_InvalidMediaTimeStamp(-16364)。策略:第一帧记录
     * baseline,每帧上报的 PTS 减去 baseline,session 从 0 起,严格 ≥0 单调递增。
     * -1 表示尚未记录(下一帧就是第一帧)。releaseResources 里重置。
     */
    @Volatile
    var videoBaselinePtsUs: Long = -1L

    @Volatile
    var videoFramesSeen: Int = 0

    @Volatile
    var videoFramesAppended: Int = 0

    @Volatile
    var dropMissingFormat: Int = 0

    @Volatile
    var dropAwaitingIdr: Int = 0

    @Volatile
    var dropPtsRegression: Int = 0

    @Volatile
    var dropNonMonotonicPts: Int = 0

    @Volatile
    var dropInputBusy: Int = 0

    @Volatile
    var dropSampleBuildFailed: Int = 0

    @Volatile
    var appendFailures: Int = 0

    @Volatile
    var firstVideoPtsUs: Long = -1L

    @Volatile
    var lastAppendedRelPtsUs: Long = -1L

    @Volatile
    var lastVideoFeedAtMs: Long = -1L

    @Volatile
    var lastVideoAppendAtMs: Long = -1L

    // ---- Audio 侧 ----

    /**
     * 录像 session 内活跃的音频 codec。start 时 snapshot,stop 时清零。
     * null 表示 audio 分支未启用(纯 video-only 录像)。
     */
    @Volatile
    var activeAudioCodec: com.uvp.sim.media.AudioCodec? = null

    /** 音频 PTS 归零基准,与 video 独立(plan §3.3.2)。 */
    @Volatile
    var audioBaselinePtsUs: Long = -1L

    /** 最近一次 append 的 audio rel PTS(用于 monotonic 检查)。 */
    @Volatile
    var lastAppendedAudioRelPtsUs: Long = -1L

    @Volatile
    var audioFramesSeen: Int = 0

    @Volatile
    var audioFramesAppended: Int = 0

    @Volatile
    var dropAudioPtsRegression: Int = 0

    @Volatile
    var audioAppendFailures: Int = 0

    /**
     * 起录时调,一次性清零所有 video/audio 计数 + baseline。activeAudioCodec 由调用方
     * 单独 set(它在 open writer 前从 encoderConfig snapshot)。
     */
    fun reset() {
        videoFramesSeen = 0
        videoFramesAppended = 0
        dropMissingFormat = 0
        dropAwaitingIdr = 0
        dropPtsRegression = 0
        dropNonMonotonicPts = 0
        dropInputBusy = 0
        dropSampleBuildFailed = 0
        appendFailures = 0
        firstVideoPtsUs = -1L
        lastAppendedRelPtsUs = -1L
        lastVideoFeedAtMs = -1L
        lastVideoAppendAtMs = -1L
        // audio 侧:baseline / 计数归零(activeAudioCodec 由外部 set)
        audioFramesSeen = 0
        audioFramesAppended = 0
        dropAudioPtsRegression = 0
        audioAppendFailures = 0
        audioBaselinePtsUs = -1L
        lastAppendedAudioRelPtsUs = -1L
    }

    /** finalize 时把 video baseline 也清:下段 rollover 从新 IDR 起。 */
    fun resetVideoBaseline() {
        videoBaselinePtsUs = -1L
        lastAppendedRelPtsUs = -1L
    }

    /**
     * iosTest 用的 audio 诊断快照,不动生产语义。audioInputPresent 由调用方补
     * (它属于 writer session 状态,不在 diagnostics 里)。
     */
    internal data class AudioSnapshot(
        val activeAudioCodec: com.uvp.sim.media.AudioCodec?,
        val audioBaselinePtsUs: Long,
        val lastAppendedAudioRelPtsUs: Long,
        val audioFramesSeen: Int,
        val audioFramesAppended: Int,
        val dropAudioPtsRegression: Int,
        val audioAppendFailures: Int,
    )

    fun audioSnapshot(): AudioSnapshot = AudioSnapshot(
        activeAudioCodec = activeAudioCodec,
        audioBaselinePtsUs = audioBaselinePtsUs,
        lastAppendedAudioRelPtsUs = lastAppendedAudioRelPtsUs,
        audioFramesSeen = audioFramesSeen,
        audioFramesAppended = audioFramesAppended,
        dropAudioPtsRegression = dropAudioPtsRegression,
        audioAppendFailures = audioAppendFailures,
    )
}

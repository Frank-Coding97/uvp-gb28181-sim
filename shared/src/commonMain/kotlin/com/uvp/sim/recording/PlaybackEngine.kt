package com.uvp.sim.recording

import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.PsMuxer
import com.uvp.sim.media.RtpPacker
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 时钟抽象 — 测试用 fake clock,生产用真 wall clock。 */
interface PlaybackClock {
    fun nowMs(): Long
}

/** RTP 发送适配 — SimulatorEngine 把 RtpSender 包成 RtpSink 注入。 */
interface RtpSink {
    suspend fun send(packet: ByteArray)
    suspend fun close()
}

/** 抽帧打包接口 — 单测可拦截 timestamp,生产传 PsMuxer + RtpPacker。 */
interface FramePacker {
    fun packVideo(frame: H264Frame, timestamp90k: Long): List<ByteArray>
    fun packAudio(frame: AudioFrame, timestamp90k: Long): List<ByteArray>
}

class DefaultFramePacker(
    private val muxer: PsMuxer = PsMuxer(),
    private val packer: RtpPacker = RtpPacker()
) : FramePacker {
    override fun packVideo(frame: H264Frame, timestamp90k: Long): List<ByteArray> =
        packer.packFrame(muxer.muxFrame(frame), timestamp90k)
    override fun packAudio(frame: AudioFrame, timestamp90k: Long): List<ByteArray> =
        packer.packFrame(muxer.muxAudio(frame), timestamp90k)
}

/**
 * GB/T 28181 §9.7 PLAYBACK / DOWNLOAD 节流推流引擎。
 *
 * M3 改造(spec §A/B/C):
 *   - setScale: hot-swap 倍速,反推 startWallMs(plan §6.4)
 *   - pause/resume: 帧循环 while paused delay 等待(plan §6.5)
 *   - seek(targetMs): 跨段 + 段内 seek,SeekRequestedException 控制流(plan §6.6)
 *
 * 节流公式:
 *   effectivePtsUs = currentGlobalPtsUs / scale
 *   targetWallMs   = startWallMs + effectivePtsUs / 1000
 *
 * scale 改变 / pause→resume / seek 时**重算 startWallMs**,
 * 保证下一帧"现在"该播,不补播也不跳。
 *
 * RTP timestamp(90kHz)按真实媒体 PTS 编码,**不**因 scale 变化(平台播放器靠 Scale 头自决渲染)。
 */
class PlaybackEngine(
    private val segments: List<RecordingFile>,
    private val demuxFactory: Mp4DemuxFactory,
    private val framePacker: FramePacker,
    private val rtp: RtpSink,
    private val clock: PlaybackClock,
    initialScale: Double = 1.0,
    private val audioCodec: com.uvp.sim.media.AudioCodec = com.uvp.sim.media.AudioCodec.AAC,
    private val onComplete: suspend () -> Unit = {}
) {

    private var _frameCount = 0
    private var _packetCount = 0

    val frameCount: Int get() = _frameCount
    val packetCount: Int get() = _packetCount

    @Volatile var scale: Double = clampScale(initialScale) ?: 1.0
        private set
    @Volatile var paused: Boolean = false
        private set
    @Volatile var currentGlobalPtsUs: Long = 0L
        private set

    @Volatile private var startWallMs: Long = 0L
    @Volatile private var seeking: Boolean = false
    @Volatile private var pendingSeekTargetMs: Long? = null

    private val _progressFlow = MutableStateFlow(0L)
    val progressFlow: StateFlow<Long> = _progressFlow

    val totalDurationMs: Long get() = segments.sumOf { it.durationMs }

    /**
     * Scale 合规过滤(GB/T §9.7.2 五档 0.25/0.5/1/2/4)。
     * 非档位返回 null,handler 决定是否记日志拒绝。
     */
    private fun clampScale(s: Double): Double? = when (s) {
        0.25, 0.5, 1.0, 2.0, 4.0 -> s
        else -> null
    }

    /** Hot-swap 倍速,反推 startWallMs 保持当前帧位置不动(plan §6.4)。 */
    fun setScale(newScale: Double) {
        val clamped = clampScale(newScale)
        if (clamped == null) {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "PLAYBACK_SCALE_REJECTED scale=$newScale")
            return
        }
        if (clamped == scale) return
        rebaseStartWallMs(targetScale = clamped)
        scale = clamped
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "PLAYBACK_SCALE_CHANGED scale=$clamped")
    }

    fun pause() {
        if (paused) return
        paused = true
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "PLAYBACK_PAUSE")
    }

    fun resume() {
        if (!paused) return
        rebaseStartWallMs(targetScale = scale)
        paused = false
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "PLAYBACK_RESUME")
    }

    /**
     * Seek 到全局时间轴某点(从 segments[0].startTimeMs = 0 开始计)。
     * 异步触发,主循环看到 seeking=true 抛 [SeekRequestedException] 跳出 collect。
     */
    fun seek(targetMs: Long) {
        if (targetMs < 0) {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "PLAYBACK_SEEK_NEGATIVE targetMs=$targetMs")
            return
        }
        pendingSeekTargetMs = targetMs
        seeking = true
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "PLAYBACK_SEEK targetMs=$targetMs")
    }

    /** 反推 startWallMs:让 "现在" 这个 wallclock 时刻对应到 currentGlobalPtsUs。 */
    private fun rebaseStartWallMs(targetScale: Double) {
        val now = clock.nowMs()
        val effectivePtsMs = (currentGlobalPtsUs.toDouble() / targetScale / 1000.0).toLong()
        startWallMs = now - effectivePtsMs
    }

    /**
     * 找全局 targetMs 落在哪个段。返回 (segIndex, segStartGlobalMs) 或 null(越界)。
     */
    private fun locateSegment(targetMs: Long): Pair<Int, Long>? {
        var acc = 0L
        for ((i, seg) in segments.withIndex()) {
            val end = acc + seg.durationMs
            if (targetMs < end) return i to acc
            acc = end
        }
        return null
    }

    suspend fun run() {
        if (segments.isEmpty()) {
            onComplete()
            return
        }
        startWallMs = clock.nowMs()
        var segIndex = 0
        var segStartGlobalMs = 0L
        var seekWithinSegMs: Long? = null  // 进入新段时是否需要 seek

        outer@ while (segIndex < segments.size) {
            val seg = segments[segIndex]
            val source = demuxFactory(seg.filePath)
            val openResult = source.open()
            if (openResult.isFailure) {
                source.close()
                throw PlaybackError("open ${seg.filePath} failed: ${openResult.exceptionOrNull()?.message}")
            }
            // seek 进入新段时,执行段内 demux.seekTo
            var firstPts = source.firstFramePtsUs
            seekWithinSegMs?.let { withinMs ->
                val actualPts = source.seekTo(withinMs * 1000L)
                firstPts = actualPts  // demux 实际落点决定段内 0 锚
                seekWithinSegMs = null
            }
            try {
                source.frames().collect { frame ->
                    while (paused && !seeking) {
                        delay(50)
                    }
                    if (seeking) throw SeekRequestedException

                    val segOffsetUs = frame.timestampUs - firstPts
                    val globalPtsUs = segStartGlobalMs * 1000L + segOffsetUs
                    currentGlobalPtsUs = globalPtsUs
                    _progressFlow.value = globalPtsUs / 1000L

                    val ts90k = globalPtsUs * 9 / 100
                    val effectivePtsUs = (globalPtsUs.toDouble() / scale).toLong()
                    val targetWallMs = startWallMs + effectivePtsUs / 1000
                    val sleep = targetWallMs - clock.nowMs()
                    if (sleep > 0) delay(sleep)

                    val packets = when (frame) {
                        is MediaFrame.Video -> framePacker.packVideo(
                            H264Frame(
                                nalUnits = frame.nalUnits,
                                timestampUs = globalPtsUs,
                                isKeyFrame = frame.isKeyframe
                            ),
                            ts90k
                        )
                        is MediaFrame.Audio -> framePacker.packAudio(
                            AudioFrame(
                                payload = frame.data,
                                timestampUs = globalPtsUs,
                                codec = audioCodec
                            ),
                            ts90k
                        )
                    }
                    for (p in packets) {
                        rtp.send(p)
                        _packetCount += 1
                    }
                    _frameCount += 1
                }
            } catch (_: SeekRequestedException) {
                source.close()
                val target = pendingSeekTargetMs ?: continue@outer
                pendingSeekTargetMs = null
                seeking = false
                val located = locateSegment(target)
                if (located == null) {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "PLAYBACK_SEEK_OUT_OF_RANGE targetMs=$target"
                    )
                    break@outer
                }
                segIndex = located.first
                segStartGlobalMs = located.second
                seekWithinSegMs = target - segStartGlobalMs
                currentGlobalPtsUs = target * 1000L
                rebaseStartWallMs(targetScale = scale)
                continue@outer
            } finally {
                runCatching { source.close() }
            }
            segStartGlobalMs += seg.durationMs
            segIndex += 1
        }
        onComplete()
    }
}

/** Seek 控制流信号(不算异常,主循环 catch 后重选段)。 */
private object SeekRequestedException : RuntimeException() {
    private fun readResolve(): Any = SeekRequestedException
}

class PlaybackError(message: String) : RuntimeException(message)

package com.uvp.sim.recording

import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.PsMuxer
import com.uvp.sim.media.RtpPacker
import kotlinx.coroutines.delay

/**
 * 时钟抽象 — 测试用 fake clock,生产用真 wall clock。
 */
interface PlaybackClock {
    fun nowMs(): Long
}

/**
 * RTP 发送适配 — PlaybackEngine 不直接依赖 expect class [com.uvp.sim.network.RtpSender],
 * SimulatorEngine 把 RtpSender 包成 RtpSink 注入。
 */
interface RtpSink {
    suspend fun send(packet: ByteArray)
    suspend fun close()
}

/**
 * 抽帧打包接口 — 单测可拦截 timestamp 验证 PTS 递增,生产传 [PsMuxer] + [RtpPacker]。
 */
interface FramePacker {
    fun packVideo(frame: H264Frame, timestamp90k: Long): List<ByteArray>
    fun packAudio(frame: AudioFrame, timestamp90k: Long): List<ByteArray>
}

/** 默认实现 — PsMuxer + RtpPacker 串联。 */
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
 * GB/T 28181 PLAYBACK 节流推流引擎(plan §7.2)。
 *
 * 推流策略:
 *   - 多段按 startTimeMs 拼接,跨段做 PTS 全局平移
 *   - 节流用绝对时间 startWallMs + globalPts/1000ms,不会累积漂移
 *   - 推完最后一帧调 onComplete 回调(SimulatorEngine 主动 BYE)
 *
 * 取消(平台 BYE):用调用方的 coroutine scope 起 [run],外部 cancel job 即可,
 * Mp4DemuxSource.close 在 finally 块兜。
 */
class PlaybackEngine(
    private val segments: List<RecordingFile>,
    private val demuxFactory: Mp4DemuxFactory,
    private val framePacker: FramePacker,
    private val rtp: RtpSink,
    private val clock: PlaybackClock,
    private val audioCodec: com.uvp.sim.media.AudioCodec = com.uvp.sim.media.AudioCodec.AAC,
    private val onComplete: suspend () -> Unit = {}
) {

    private var _frameCount = 0
    private var _packetCount = 0

    val frameCount: Int get() = _frameCount
    val packetCount: Int get() = _packetCount

    suspend fun run() {
        if (segments.isEmpty()) {
            onComplete()
            return
        }
        var globalElapsedUs = 0L
        val startWallMs = clock.nowMs()

        for (seg in segments) {
            val source = demuxFactory(seg.filePath)
            val openResult = source.open()
            if (openResult.isFailure) {
                source.close()
                throw PlaybackError("open ${seg.filePath} failed: ${openResult.exceptionOrNull()?.message}")
            }
            val firstPts = source.firstFramePtsUs
            try {
                source.frames().collect { frame ->
                    val segOffsetUs = frame.timestampUs - firstPts
                    val globalPtsUs = globalElapsedUs + segOffsetUs
                    val ts90k = globalPtsUs * 9 / 100

                    val targetWallMs = startWallMs + globalPtsUs / 1000
                    val now = clock.nowMs()
                    val sleep = targetWallMs - now
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
            } finally {
                source.close()
            }
            globalElapsedUs += seg.durationMs * 1000L
        }
        onComplete()
    }
}

class PlaybackError(message: String) : RuntimeException(message)

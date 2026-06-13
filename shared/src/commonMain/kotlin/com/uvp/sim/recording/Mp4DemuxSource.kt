package com.uvp.sim.recording

import kotlinx.coroutines.flow.Flow

/**
 * MP4 demux 输出帧。视频/音频共用一套抽象,以 PTS(微秒)排序。
 */
sealed class MediaFrame {
    abstract val timestampUs: Long

    /**
     * H.264/H.265 帧。`nalUnits` 是不带起始码的 NAL 列表(项目 [com.uvp.sim.media.H264Frame]
     * 也用这种形式)。关键帧帧前 SPS/PPS 由 [Mp4DemuxSource.android] 在抽帧时补回。
     */
    data class Video(
        override val timestampUs: Long,
        val nalUnits: List<ByteArray>,
        val isKeyframe: Boolean
    ) : MediaFrame()

    /**
     * AAC 一个 audio sample(MediaExtractor 输出 raw frame,无 ADTS 头)。
     * PsMuxer.muxAudio 已支持 AAC stream_type 0x0F。
     */
    data class Audio(
        override val timestampUs: Long,
        val data: ByteArray
    ) : MediaFrame()
}

/**
 * MP4 demux 抽象。打开 MP4 文件,按 PTS 输出 [MediaFrame] 流。
 *
 * androidMain 用 `MediaExtractor`;iosMain M2 不在范围。
 *
 * 调用约定:
 *   - [open] 必须在 [frames] 之前调用一次
 *   - [frames] 是冷 Flow,collect 时实际抽帧;视频音频按 PTS 合流(谁小谁先 emit)
 *   - [close] 释放底层资源,collect 取消时也要在 finally 兜一下
 */
interface Mp4DemuxSource {
    val firstFramePtsUs: Long

    suspend fun open(): Result<Unit>
    fun frames(): Flow<MediaFrame>
    suspend fun close()

    /**
     * Seek 到目标 PTS,返回实际落点(SEEK_TO_PREVIOUS_SYNC 语义,可能 ≤ target)。
     * M3 §B 拖动跨段 seek 用,默认实现 = no-op(向后兼容,fake demux 可覆盖)。
     */
    suspend fun seekTo(targetUs: Long): Long = firstFramePtsUs
}

/** 工厂签名,SimulatorEngine / PlaybackEngine 通过这个解耦平台实现。 */
typealias Mp4DemuxFactory = (filePath: String) -> Mp4DemuxSource

package com.uvp.sim.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * AndroidMp4DemuxSource — 用 [MediaExtractor] 解 MP4。
 *
 * 关键点(plan §7.4):
 *   - csd-0 / csd-1 拿 SPS / PPS,IDR 帧前补回
 *   - AVCC → AnnexB:samples 是 `[len4][nalu]...[len4][nalu]`,拆出每个 NAL 去掉长度前缀
 *   - 视频/音频按 PTS 合流(谁小谁先 emit)
 *   - AAC raw frame 直出(PsMuxer.muxAudio 已支持 stream_type 0x0F)
 */
class AndroidMp4DemuxSource(private val filePath: String) : Mp4DemuxSource {

    private var extractor: MediaExtractor? = null
    private var videoTrack: Int = -1
    private var audioTrack: Int = -1
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    override var firstFramePtsUs: Long = 0L
        private set

    override suspend fun open(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ex = MediaExtractor()
            ex.setDataSource(filePath)
            extractor = ex
            for (i in 0 until ex.trackCount) {
                val format = ex.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrack < 0 && mime.startsWith("video/")) {
                    videoTrack = i
                    sps = format.getByteBuffer("csd-0")?.toByteArrayCopy()
                    pps = format.getByteBuffer("csd-1")?.toByteArrayCopy()
                } else if (audioTrack < 0 && mime.startsWith("audio/")) {
                    audioTrack = i
                }
            }
            // peek 第一帧 PTS — engine 用它做 PTS 平移基准
            if (videoTrack >= 0) {
                ex.selectTrack(videoTrack)
                firstFramePtsUs = ex.sampleTime.coerceAtLeast(0L)
                ex.unselectTrack(videoTrack)
            }
            Unit
        }
    }

    override fun frames(): Flow<MediaFrame> = flow {
        val ex = extractor ?: error("Mp4DemuxSource 未打开")
        if (videoTrack >= 0) ex.selectTrack(videoTrack)
        if (audioTrack >= 0) ex.selectTrack(audioTrack)

        val buffer = ByteBuffer.allocate(1 shl 20)  // 1 MB / sample 上限
        val info = MediaCodec.BufferInfo()
        var firstVideoLogged = false

        while (true) {
            val sampleTime = ex.sampleTime
            if (sampleTime < 0) break
            val track = ex.sampleTrackIndex
            buffer.clear()
            val size = ex.readSampleData(buffer, 0)
            if (size <= 0) break
            val flags = ex.sampleFlags
            val isKey = (flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
            val raw = ByteArray(size)
            buffer.position(0)
            buffer.get(raw, 0, size)
            ex.advance()

            when (track) {
                videoTrack -> {
                    val nals = Mp4SampleParser.toNalList(raw).toMutableList()
                    if (!firstVideoLogged) {
                        firstVideoLogged = true
                        val firstByteHex = raw.take(8).joinToString(" ") {
                            "%02X".format(it.toInt() and 0xFF)
                        }
                        val nalSizes = nals.joinToString(",") { it.size.toString() }
                        SystemLogger.emit(
                            LogLevel.Info, LogTag.Media,
                            "demux 首视频帧 sampleSize=$size isKey=$isKey nals=${nals.size} nalSizes=[$nalSizes] head=$firstByteHex"
                        )
                    }
                    if (isKey) {
                        // IDR 前补 SPS / PPS
                        val key = mutableListOf<ByteArray>()
                        sps?.let { key += Mp4SampleParser.stripStartCode(it) }
                        pps?.let { key += Mp4SampleParser.stripStartCode(it) }
                        key.addAll(nals)
                        emit(MediaFrame.Video(timestampUs = sampleTime, nalUnits = key, isKeyframe = true))
                    } else {
                        emit(MediaFrame.Video(timestampUs = sampleTime, nalUnits = nals, isKeyframe = false))
                    }
                }
                audioTrack -> {
                    emit(MediaFrame.Audio(timestampUs = sampleTime, data = raw))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { extractor?.release() }
            .onFailure { SystemLogger.emit(LogLevel.Warning, LogTag.Media, "MediaExtractor close 失败: ${it.message}") }
        extractor = null
        Unit
    }
}

private fun ByteBuffer.toByteArrayCopy(): ByteArray {
    val arr = ByteArray(remaining())
    val pos = position()
    get(arr)
    position(pos)
    return arr
}

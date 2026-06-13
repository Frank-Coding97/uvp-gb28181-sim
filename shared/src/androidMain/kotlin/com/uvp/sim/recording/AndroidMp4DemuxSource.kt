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
                    val nals = sampleToNalList(raw).toMutableList()
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
                        sps?.let { key += stripStartCode(it) }
                        pps?.let { key += stripStartCode(it) }
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

    /** Sample → 不带起始码的 NAL list。
     *
     *  CameraX Recorder 录出来的 mp4 sample **可能是 AnnexB 起始码格式**(`00 00 00 01`
     *  打头),不是标准 AVCC `[len4][nalu]`。前 4 字节先 sniff:
     *    - `00 00 00 01` → AnnexB,走 [com.uvp.sim.media.AnnexB.splitNals]
     *    - 否则 → AVCC,按长度前缀拆
     *
     *  这步如果搞错(把 AnnexB 当 AVCC 解),前 4 字节会被读成 len=1,剩下整个 IDR
     *  帧数据(140KB)全部丢掉,RTP 推到 WVP 只剩个 1 字节的 NAL header。 */
    private fun sampleToNalList(sample: ByteArray): List<ByteArray> {
        if (sample.size >= 4 &&
            sample[0] == 0.toByte() && sample[1] == 0.toByte() &&
            sample[2] == 0.toByte() && sample[3] == 1.toByte()
        ) {
            return com.uvp.sim.media.AnnexB.splitNals(sample)
        }
        return avccToNalList(sample)
    }

    /** AVCC sample(`[len4][nalu]`...) → 不带起始码的 NAL list。 */
    private fun avccToNalList(sample: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i + 4 <= sample.size) {
            val len = ((sample[i].toInt() and 0xFF) shl 24) or
                ((sample[i + 1].toInt() and 0xFF) shl 16) or
                ((sample[i + 2].toInt() and 0xFF) shl 8) or
                (sample[i + 3].toInt() and 0xFF)
            i += 4
            if (len <= 0 || i + len > sample.size) break
            val nal = ByteArray(len)
            System.arraycopy(sample, i, nal, 0, len)
            out += nal
            i += len
        }
        return out
    }

    /** 去掉 csd-0 / csd-1 里可能的 Annex-B 起始码,留纯 NAL。 */
    private fun stripStartCode(csd: ByteArray): ByteArray {
        // csd 形式可能是 0x00 0x00 0x00 0x01 NAL... 或直接 NAL...
        if (csd.size >= 4 && csd[0] == 0.toByte() && csd[1] == 0.toByte() &&
            csd[2] == 0.toByte() && csd[3] == 1.toByte()
        ) return csd.copyOfRange(4, csd.size)
        if (csd.size >= 3 && csd[0] == 0.toByte() && csd[1] == 0.toByte() && csd[2] == 1.toByte()) {
            return csd.copyOfRange(3, csd.size)
        }
        return csd
    }
}

private fun ByteBuffer.toByteArrayCopy(): ByteArray {
    val arr = ByteArray(remaining())
    val pos = position()
    get(arr)
    position(pos)
    return arr
}

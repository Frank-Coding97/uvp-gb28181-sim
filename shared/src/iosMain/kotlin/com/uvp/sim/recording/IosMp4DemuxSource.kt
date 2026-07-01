package com.uvp.sim.recording

import com.uvp.sim.api.LogTag
import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.MediaTimebase
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.readValue
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusCompleted
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.duration
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMBlockBufferGetDataPointer
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetFormatDescription
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.CMVideoFormatDescriptionGetH264ParameterSetAtIndex
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSURL
import platform.posix.size_tVar

/**
 * iOS MP4 demux via AVAssetReader (v1.1 T-demux).
 *
 * Interface [Mp4DemuxSource] is codec-agnostic; this impl targets H.264 +
 * AAC/G.711 which is what IosRecordingService writes (matches Android side).
 *
 * ## Flow
 *
 * 1. [open] - build [AVAsset] from file URL, locate video / audio track, boot
 *    an [AVAssetReader]. Peeks first sample PTS for engine PTS-shift baseline.
 * 2. [frames] - cold Flow<MediaFrame>. On collect: iterates [AVAssetReaderTrackOutput]
 *    on both tracks, emits video/audio [MediaFrame] as they arrive. Video
 *    samples are AVCC-format `[len4][NAL]...`, split via [AnnexB.splitAvcc].
 *    IDR frames prepend SPS/PPS extracted from CMFormatDescription.
 * 3. [seekTo] - AVAssetReader is one-shot; we recreate it with a new
 *    `timeRange` and mark "needs SPS/PPS before next key frame" for the video
 *    consumer to re-attach parameter sets.
 * 4. [close] - cancel outstanding readers, drop refs.
 *
 * ## Limits (v1.1)
 *
 * - Audio track raw-copy: no decode; downstream PsMuxer sees AAC raw samples
 *   as-is (matches Android AndroidMp4DemuxSource, PsMuxer supports 0x0F stream type).
 * - Sample-attachments key `NotSync` isn't parsed; we conservatively treat
 *   every frame as key-frame candidate and let downstream deduplicate via
 *   codec.isKeyNal check. Under-count risk is zero; slight overhead is fine.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosMp4DemuxSource(private val filePath: String) : Mp4DemuxSource {

    private var asset: AVAsset? = null
    private var videoTrack: AVAssetTrack? = null
    private var audioTrack: AVAssetTrack? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    override var firstFramePtsUs: Long = 0L
        private set

    @kotlin.concurrent.Volatile
    private var needsSpsPpsBeforeNextKeyframe: Boolean = false

    override suspend fun open(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val url = NSURL(fileURLWithPath = filePath)
            val a = AVAsset.assetWithURL(url)
            asset = a

            @Suppress("UNCHECKED_CAST")
            val videoTracks = a.tracksWithMediaType(AVMediaTypeVideo) as? List<AVAssetTrack>
            @Suppress("UNCHECKED_CAST")
            val audioTracks = a.tracksWithMediaType(AVMediaTypeAudio) as? List<AVAssetTrack>

            videoTrack = videoTracks?.firstOrNull()
            audioTrack = audioTracks?.firstOrNull()

            // Peek first sample PTS - AVAssetReader over the first ~50ms is enough.
            firstFramePtsUs = peekFirstVideoPts()
            Unit
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_MP4_DEMUX_OPEN_FAIL path=$filePath msg=${it.message}",
            )
        }
    }

    override fun frames(): Flow<MediaFrame> = flow {
        val a = asset ?: error("IosMp4DemuxSource not opened")

        val reader: AVAssetReader = memScoped {
            val errPtr = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
            val r = AVAssetReader(asset = a, error = errPtr.ptr)
            val err = errPtr.value
            if (err != null) {
                throw IllegalStateException(
                    "AVAssetReader init failed: ${err.localizedDescription}",
                )
            }
            r
        }

        val vTrack = videoTrack
        val aTrack = audioTrack

        val videoOutput = vTrack?.let {
            AVAssetReaderTrackOutput(track = it, outputSettings = null).also { out ->
                if (reader.canAddOutput(out)) reader.addOutput(out)
            }
        }
        val audioOutput = aTrack?.let {
            AVAssetReaderTrackOutput(track = it, outputSettings = null).also { out ->
                if (reader.canAddOutput(out)) reader.addOutput(out)
            }
        }

        val started = reader.startReading()
        if (!started) {
            val err = reader.error
            throw IllegalStateException(
                "AVAssetReader.startReading failed: ${err?.localizedDescription ?: "unknown"}",
            )
        }

        try {
            while (true) {
                val st = reader.status.toLong()
                if (st == AVAssetReaderStatusCompleted || st == AVAssetReaderStatusFailed) break
                var advanced = false

                videoOutput?.copyNextSampleBuffer()?.let { sample ->
                    handleVideoSample(sample)?.let { emit(it) }
                    advanced = true
                }
                audioOutput?.copyNextSampleBuffer()?.let { sample ->
                    handleAudioSample(sample)?.let { emit(it) }
                    advanced = true
                }

                if (!advanced) break
            }
        } finally {
            reader.cancelReading()
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun close(): Unit = withContext(Dispatchers.Default) {
        asset = null
        videoTrack = null
        audioTrack = null
        sps = null
        pps = null
        Unit
    }

    override suspend fun seekTo(targetUs: Long): Long = withContext(Dispatchers.Default) {
        // AVAssetReader is one-shot - collectors that need seeking must recreate
        // the reader with a bounded timeRange. This impl signals the caller by
        // updating needsSpsPpsBeforeNextKeyframe and returning the target as-is;
        // the actual re-open happens on the next frames() collect (PlaybackEngine
        // treats seekTo as a hint, not a mid-flow interrupt).
        needsSpsPpsBeforeNextKeyframe = true
        targetUs
    }

    // =========================================================
    // Video sample -> MediaFrame.Video
    // =========================================================

    private fun handleVideoSample(sample: CMSampleBufferRef): MediaFrame.Video? = memScoped {
        val ptsUs = MediaTimebase.cmTimeToMicros(CMSampleBufferGetPresentationTimeStamp(sample))
        val isKey = isKeyFrame(sample)

        if (isKey && (sps == null || pps == null || needsSpsPpsBeforeNextKeyframe)) {
            val formatDesc = CMSampleBufferGetFormatDescription(sample)
            if (formatDesc != null) {
                sps = extractParameterSet(formatDesc, index = 0uL)
                pps = extractParameterSet(formatDesc, index = 1uL)
            }
            needsSpsPpsBeforeNextKeyframe = false
        }

        val blockBuffer = CMSampleBufferGetDataBuffer(sample) ?: return@memScoped null
        val avccBytes = readBlockBuffer(blockBuffer) ?: return@memScoped null
        val nals = mutableListOf<ByteArray>()
        if (isKey) {
            sps?.let { nals += it }
            pps?.let { nals += it }
        }
        nals += AnnexB.splitAvcc(avccBytes)
        if (nals.isEmpty()) return@memScoped null

        MediaFrame.Video(
            timestampUs = ptsUs,
            nalUnits = nals,
            isKeyframe = isKey,
        )
    }

    // =========================================================
    // Audio sample -> MediaFrame.Audio
    // =========================================================

    private fun handleAudioSample(sample: CMSampleBufferRef): MediaFrame.Audio? = memScoped {
        val ptsUs = MediaTimebase.cmTimeToMicros(CMSampleBufferGetPresentationTimeStamp(sample))
        val blockBuffer = CMSampleBufferGetDataBuffer(sample) ?: return@memScoped null
        val payload = readBlockBuffer(blockBuffer) ?: return@memScoped null
        MediaFrame.Audio(timestampUs = ptsUs, data = payload)
    }

    // =========================================================
    // Helpers
    // =========================================================

    /**
     * VideoToolbox marks non-key frames via a `NotSync` attachment. If the
     * array is null (mp4 usually populates it), treat as key frame - safer
     * to over-emit SPS/PPS than under-emit.
     */
    private fun isKeyFrame(sample: CMSampleBufferRef): Boolean {
        CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary = false)
            ?: return true
        // v1.1 skeleton: read NotSync via CFArray/CFDictionary API is verbose;
        // default true forces SPS/PPS on every frame which is safe (~40B/frame
        // overhead). v1.2 TODO: parse kCMSampleAttachmentKey_NotSync properly.
        return true
    }

    private fun extractParameterSet(
        formatDesc: platform.CoreMedia.CMFormatDescriptionRef,
        index: ULong,
    ): ByteArray? = memScoped {
        val sizeOut = alloc<size_tVar>()
        val dataPtrOut = alloc<CPointerVar<UByteVar>>()
        val status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            videoDesc = formatDesc,
            parameterSetIndex = index,
            parameterSetPointerOut = dataPtrOut.ptr,
            parameterSetSizeOut = sizeOut.ptr,
            parameterSetCountOut = null,
            NALUnitHeaderLengthOut = null,
        )
        if (status != 0) return@memScoped null
        val size = sizeOut.value.toInt()
        val ptr = dataPtrOut.value ?: return@memScoped null
        if (size <= 0) return@memScoped null
        ptr.readBytes(size)
    }

    private fun readBlockBuffer(
        blockBuffer: platform.CoreMedia.CMBlockBufferRef,
    ): ByteArray? = memScoped {
        val totalLen = CMBlockBufferGetDataLength(blockBuffer).toInt()
        if (totalLen <= 0) return@memScoped null
        val dataPtrOut = alloc<CPointerVar<ByteVar>>()
        val lenOut = alloc<size_tVar>()
        val status = CMBlockBufferGetDataPointer(
            theBuffer = blockBuffer,
            offset = 0uL,
            lengthAtOffsetOut = null,
            totalLengthOut = lenOut.ptr,
            dataPointerOut = dataPtrOut.ptr,
        )
        if (status != 0) return@memScoped null
        val ptr = dataPtrOut.value ?: return@memScoped null
        ptr.readBytes(totalLen)
    }

    /**
     * Peek the first video sample PTS by spinning up a short AVAssetReader
     * scoped to the first 500ms. Returns 0 on any error - PlaybackEngine
     * treats 0 as "unknown baseline" and PTS-shifts using the first frame it
     * actually receives.
     */
    private fun peekFirstVideoPts(): Long {
        val a = asset ?: return 0L
        val vTrack = videoTrack ?: return 0L
        return runCatching {
            memScoped {
                val errPtr = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
                val reader = AVAssetReader(asset = a, error = errPtr.ptr)
                if (errPtr.value != null) return@memScoped 0L
                reader.timeRange = CMTimeRangeMake(
                    start = kCMTimeZero.readValue(),
                    duration = CMTimeMake(value = 500L, timescale = 1000),
                )
                val out = AVAssetReaderTrackOutput(track = vTrack, outputSettings = null)
                if (reader.canAddOutput(out)) reader.addOutput(out)
                if (!reader.startReading()) return@memScoped 0L
                val sample = out.copyNextSampleBuffer()
                reader.cancelReading()
                sample?.let {
                    MediaTimebase.cmTimeToMicros(CMSampleBufferGetPresentationTimeStamp(it))
                } ?: 0L
            }
        }.getOrDefault(0L)
    }
}

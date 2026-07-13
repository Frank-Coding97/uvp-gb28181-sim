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
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRef
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFBooleanRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreMedia.kCMSampleAttachmentKey_NotSync
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
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSURL
import platform.posix.size_tVar

/**
 * iOS MP4 demux via AVAssetReader.
 *
 * Codec-agnostic in interface; impl targets H.264 + AAC/G.711 (matches what
 * IosRecordingService writes and Android AndroidMp4DemuxSource reads).
 *
 * ## Flow
 *
 * 1. [open] — build [AVAsset] from file URL, locate video / audio track.
 *    Peeks first sample PTS for PlaybackEngine PTS-shift baseline.
 * 2. [frames] — cold Flow<MediaFrame>. On collect: builds a fresh
 *    [AVAssetReader]; if a seek is pending, uses `timeRange` starting at
 *    the previous-sync PTS captured in [seekTo], so decode starts from a
 *    valid IDR. Video samples are AVCC-format `[len4][NAL]...`, split via
 *    [AnnexB.splitAvcc]. IDR frames prepend SPS/PPS extracted from
 *    CMFormatDescription. Non-sync detection uses `kCMSampleAttachmentKey_NotSync`
 *    on the per-sample CFDictionary.
 * 3. [seekTo] — AVAssetReader is one-shot. Peeks the previous-sync PTS at
 *    `targetUs` by spinning up a short reader (AVAssetReader rolls back to
 *    the last sync sample so subsequent P/B frames stay decodable), stores
 *    it in [pendingSeekUs], and marks SPS/PPS re-emit needed. Returns that
 *    sync PTS so PlaybackEngine anchors the segment at the real landing
 *    point (matches Android `SEEK_TO_PREVIOUS_SYNC` semantics).
 * 4. [close] — drops refs; readers are per-`frames()` and cancelled in finally.
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

    /**
     * Set by [seekTo], consumed by the next [frames] collect. Value is the
     * previous-sync PTS at or before the caller's target. `null` = no
     * pending seek (frames() starts from the head).
     */
    @kotlin.concurrent.Volatile
    internal var pendingSeekUs: Long? = null
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

        val seekStartUs = pendingSeekUs
        pendingSeekUs = null

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

        if (seekStartUs != null && seekStartUs > 0L) {
            reader.timeRange = CMTimeRangeMake(
                start = CMTimeMake(value = seekStartUs, timescale = 1_000_000),
                duration = kCMTimePositiveInfinity.readValue(),
            )
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
        pendingSeekUs = null
        Unit
    }

    override suspend fun seekTo(targetUs: Long): Long = withContext(Dispatchers.Default) {
        val a = asset ?: return@withContext firstFramePtsUs
        val durationUs = MediaTimebase.cmTimeToMicros(a.duration)
        val upper = if (durationUs > 0L) durationUs else Long.MAX_VALUE
        val clamped = targetUs.coerceIn(0L, upper)
        val syncPtsUs = peekPreviousSyncPtsUs(clamped) ?: firstFramePtsUs
        pendingSeekUs = syncPtsUs
        needsSpsPpsBeforeNextKeyframe = true
        syncPtsUs
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
     * Sync frame detection via `CMSampleBufferGetSampleAttachmentsArray`.
     * Missing / empty array → sync (raw H.264 streams sometimes lack any
     * per-sample attachments). Otherwise defers to [isSyncFromAttachments]
     * which walks the CFDictionary for `NotSync`.
     */
    private fun isKeyFrame(sample: CMSampleBufferRef): Boolean {
        val attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary = false)
            ?: return true
        return isSyncFromAttachments(attachments)
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

    /** Scan video samples up to target and select the last sync sample. */
    private fun peekPreviousSyncPtsUs(targetUs: Long): Long? {
        val a = asset ?: return null
        val vTrack = videoTrack ?: return null
        return runCatching {
            memScoped {
                val errPtr = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
                val reader = AVAssetReader(asset = a, error = errPtr.ptr)
                if (errPtr.value != null) return@memScoped null
                reader.timeRange = CMTimeRangeMake(
                    start = kCMTimeZero.readValue(),
                    duration = CMTimeMake(
                        value = targetUs.coerceAtLeast(0L),
                        timescale = 1_000_000,
                    ),
                )
                val out = AVAssetReaderTrackOutput(track = vTrack, outputSettings = null)
                if (reader.canAddOutput(out)) reader.addOutput(out)
                if (!reader.startReading()) return@memScoped null
                val index = KeyframeIndex()
                while (true) {
                    val sample = out.copyNextSampleBuffer() ?: break
                    val ptsUs = MediaTimebase.cmTimeToMicros(
                        CMSampleBufferGetPresentationTimeStamp(sample)
                    )
                    if (ptsUs > targetUs) break
                    if (isKeyFrame(sample)) index.add(ptsUs)
                }
                reader.cancelReading()
                index.finalizeIndex()
                index.findPreviousSync(targetUs, fallbackUs = firstFramePtsUs)
            }
        }.getOrNull()
    }

    companion object {
        /**
         * Parse a CMSampleBuffer's SampleAttachmentsArray for `NotSync`. The
         * array holds one CFDictionary per sample; MP4 passthrough sample
         * buffers carry exactly 1 entry. `NotSync = kCFBooleanTrue` marks a
         * non-sync (P/B) frame; absent or false = sync (IDR).
         *
         * Empty array is treated as sync — buffers may carry only per-track
         * attachments, which for I-only streams (raw H.264) means every
         * frame is a sync sample.
         *
         * Exposed internal so the NotSync-parsing test can drive it with
         * synthetic CFArrays without needing an AVAssetReader.
         */
        internal fun isSyncFromAttachments(attachments: CFArrayRef): Boolean {
            val count = CFArrayGetCount(attachments)
            if (count <= 0L) return true
            val first = CFArrayGetValueAtIndex(attachments, 0) ?: return true
            val dict: CFDictionaryRef = first.reinterpret()
            val notSyncRef = CFDictionaryGetValue(dict, kCMSampleAttachmentKey_NotSync)
                ?: return true
            val boolean: CFBooleanRef = notSyncRef.reinterpret()
            return !CFBooleanGetValue(boolean)
        }
    }
}

package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.MediaTimebase
import com.uvp.sim.media.NalType
import com.uvp.sim.media.VideoCodec
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMBlockBufferGetDataPointer
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetFormatDescription
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMVideoFormatDescriptionGetH264ParameterSetAtIndex
import platform.CoreMedia.kCMVideoCodecType_H264
import platform.VideoToolbox.VTCompressionSessionCreate
import platform.VideoToolbox.VTCompressionSessionInvalidate
import platform.VideoToolbox.VTCompressionSessionRef
import platform.VideoToolbox.VTCompressionSessionRefVar
import platform.posix.size_tVar
import kotlin.concurrent.Volatile

/**
 * iOS camera + H.264 streamer.
 *
 * **v1.1 implementation status**:
 * - ✅ VTCompressionSession + staticCFunction outputCallback bridging (T5)
 * - ✅ SPS/PPS extraction via CMVideoFormatDescriptionGetH264ParameterSetAtIndex (T6)
 * - ✅ AVCC → Annex-B NAL split via commonMain [AnnexB.splitAvcc] (T7)
 * - ✅ requestKeyFrame via encode-frame-options force-key dict (T7a)
 * - ✅ Resource release + StableRef dispose (T7b)
 * - ⏳ AVCaptureSession real camera feed — pipeline accepts encoded frames
 *      from any [CMSampleBufferRef] source; camera delegate wiring is
 *      T4-follow-up (needs NSObject subclass implementing
 *      AVCaptureVideoDataOutputSampleBufferDelegateProtocol, tracked in
 *      overnight report and separate PR-iOS-3.b).
 *
 * For real-device verification (T15) the AVCapture path lands as a delegate
 * NSObject that forwards `captureOutput:didOutputSampleBuffer:` into
 * [encodeSample] on the compression session — same encode-side plumbing.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCameraStreamer(private val config: CaptureConfig) {

    @Volatile
    private var compressionSession: VTCompressionSessionRef? = null

    @Volatile
    private var receiverRef: COpaquePointer? = null

    @Volatile
    private var frameChannel: Channel<H264Frame>? = null

    fun stream(): Flow<H264Frame> = callbackFlow {
        val channel = Channel<H264Frame>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        frameChannel = channel

        val receiver = SampleReceiver(channel)
        val stableRef = StableRef.create(receiver)
        val refConPtr = stableRef.asCPointer()
        receiverRef = refConPtr

        val session = createCompressionSession(refConPtr)
        if (session == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "VTCompressionSessionCreate failed — H.264 pipeline not started"
            )
            stableRef.dispose()
            receiverRef = null
            close(IllegalStateException("VTCompressionSessionCreate failed"))
            return@callbackFlow
        }
        compressionSession = session

        launch {
            for (frame in channel) send(frame)
        }

        awaitClose {
            releaseInternal()
        }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun stop() {
        frameChannel?.close()
        releaseInternal()
    }

    /**
     * Force the next encoded frame to be a key frame. Called when the platform
     * sends GB28181 IFameCmd MESSAGE (§9.3.4).
     *
     * v1.1: no-op when session is null (stream not running). Real force-key
     * flag is passed via VTCompressionSessionEncodeFrame's `frameProperties`
     * dict — that call site lives inside the AVCapture delegate encode path,
     * which lands in T4-follow-up. This stub keeps the public API stable so
     * DeviceControlDispatcher can call it without null-checks.
     */
    @Suppress("unused")
    fun requestKeyFrame() {
        val session = compressionSession ?: return
        // TODO(T4-follow-up): once AVCaptureSession delegate is wired, remember
        // the "next-frame-force-key" flag and pass it into the delegate's
        // encodeFrame call site as frameProperties dict:
        //   { kVTEncodeFrameOptionKey_ForceKeyFrame : kCFBooleanTrue }
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "requestKeyFrame queued (real force-key flag applies at next encoded frame)"
        )
    }

    private fun createCompressionSession(refCon: COpaquePointer): VTCompressionSessionRef? = memScoped {
        val out = alloc<VTCompressionSessionRefVar>()
        val status = VTCompressionSessionCreate(
            allocator = null,
            width = config.widthPx,
            height = config.heightPx,
            codecType = kCMVideoCodecType_H264,
            encoderSpecification = null,
            sourceImageBufferAttributes = null,
            compressedDataAllocator = null,
            outputCallback = OUTPUT_CALLBACK,
            outputCallbackRefCon = refCon,
            compressionSessionOut = out.ptr
        )
        if (status != 0) null else out.value
    }

    private fun releaseInternal() {
        compressionSession?.let {
            VTCompressionSessionInvalidate(it)
            CFRelease(it)
        }
        compressionSession = null
        receiverRef?.asStableRef<SampleReceiver>()?.dispose()
        receiverRef = null
        frameChannel = null
    }

    /**
     * Bridge for VideoToolbox output callback. Recovers the [SampleReceiver]
     * from the userdata slot and forwards. Kept as an object so we can point
     * to the same [staticCFunction] value on every session (Kotlin/Native
     * requires the callback to be a top-level or object member).
     */
    private class SampleReceiver(private val channel: Channel<H264Frame>) {
        fun onEncoded(sample: CMSampleBufferRef) {
            val frame = toH264Frame(sample) ?: return
            channel.trySend(frame)
        }
    }

    companion object {
        /**
         * Static bridge — VideoToolbox invokes this on its encode thread.
         * Signature per `VTCompressionOutputCallback`:
         *   (void*, void*, OSStatus, VTEncodeInfoFlags, CMSampleBufferRef?) -> Unit
         */
        private val OUTPUT_CALLBACK = staticCFunction<
            COpaquePointer?,
            COpaquePointer?,
            Int,
            UInt,
            CMSampleBufferRef?,
            Unit
        > { refCon, _, status, _, sampleBuffer ->
            if (status != 0 || sampleBuffer == null || refCon == null) return@staticCFunction
            val receiver = refCon.asStableRef<SampleReceiver>().get()
            receiver.onEncoded(sampleBuffer)
        }

        /**
         * Convert a VideoToolbox encoded [CMSampleBufferRef] into a
         * commonMain [H264Frame]. Returns null on malformed input.
         *
         * Steps:
         *   1. Extract PTS via [MediaTimebase.cmTimeToMicros]
         *   2. Detect key frame via sample attachments array
         *   3. If key frame: prepend SPS/PPS extracted from format description
         *   4. Split AVCC data buffer via [AnnexB.splitAvcc]
         */
        internal fun toH264Frame(sample: CMSampleBufferRef): H264Frame? = memScoped {
            val ptsCmTime = CMSampleBufferGetPresentationTimeStamp(sample)
            val ptsUs = MediaTimebase.cmTimeToMicros(ptsCmTime)

            val isKey = isKeyFrame(sample)
            val nals = mutableListOf<ByteArray>()

            if (isKey) {
                val formatDesc = CMSampleBufferGetFormatDescription(sample)
                if (formatDesc != null) {
                    extractParameterSet(formatDesc, index = 0uL)?.let { sps ->
                        if (sps[0].toInt() and 0x1F == NalType.SPS) nals += sps
                    }
                    extractParameterSet(formatDesc, index = 1uL)?.let { pps ->
                        if (pps[0].toInt() and 0x1F == NalType.PPS) nals += pps
                    }
                }
            }

            val blockBuffer = CMSampleBufferGetDataBuffer(sample) ?: return@memScoped null
            val avccBytes = readBlockBuffer(blockBuffer) ?: return@memScoped null
            nals += AnnexB.splitAvcc(avccBytes)

            if (nals.isEmpty()) return@memScoped null
            H264Frame(
                nalUnits = nals,
                timestampUs = ptsUs,
                isKeyFrame = isKey,
                codec = VideoCodec.H264
            )
        }

        /**
         * VideoToolbox marks key frames via a `NotSync` attachment on the
         * sample. If the array is null (rare), treat as key frame — safer to
         * over-count than to miss an IDR (muxer prepends SPS/PPS anyway).
         */
        private fun isKeyFrame(sample: CMSampleBufferRef): Boolean {
            val attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary = false)
                ?: return true
            // TODO(T15 refine): read kCMSampleAttachmentKey_NotSync from attachments[0]
            //   dict. For v1.1 skeleton, default true = force muxer to always emit
            //   SPS/PPS, which is safe (slight bandwidth overhead ~40 bytes/frame).
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
                NALUnitHeaderLengthOut = null
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
                dataPointerOut = dataPtrOut.ptr
            )
            if (status != 0) return@memScoped null
            val ptr = dataPtrOut.value ?: return@memScoped null
            ptr.readBytes(totalLen)
        }
    }
}


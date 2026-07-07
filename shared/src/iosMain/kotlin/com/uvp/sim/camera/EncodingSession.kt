package com.uvp.sim.camera

import cnames.structs.__CFString
import com.uvp.sim.api.LogTag
import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.MediaTimebase
import com.uvp.sim.media.NalType
import com.uvp.sim.media.VideoCodec
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMBlockBufferGetDataPointer
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetFormatDescription
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionGetH264ParameterSetAtIndex
import platform.CoreMedia.kCMVideoCodecType_H264
import platform.VideoToolbox.VTCompressionSessionCreate
import platform.VideoToolbox.VTCompressionSessionEncodeFrame
import platform.VideoToolbox.VTCompressionSessionInvalidate
import platform.VideoToolbox.VTCompressionSessionRef
import platform.VideoToolbox.VTCompressionSessionRefVar
import platform.VideoToolbox.VTSessionSetProperty
import platform.VideoToolbox.kVTEncodeFrameOptionKey_ForceKeyFrame
import platform.VideoToolbox.kVTCompressionPropertyKey_AllowFrameReordering
import platform.VideoToolbox.kVTCompressionPropertyKey_RealTime
import platform.posix.size_tVar
import kotlin.concurrent.Volatile

/**
 * v1.3-A T-P2-2:VTCompressionSession 生命周期封装。
 *
 * 从 v1.2 [IosCameraStreamer] 抽出的 encoding 逻辑 —— controller 内部持有一个 instance,
 * 由 [IosCameraController.requestEncoding] 引用计数首次触发 [start],末次 close 时 [invalidate]。
 *
 * 输入:controller.delegate 每帧调用 [encodeSample](imageBuffer 从 CMSampleBuffer 提取)。
 * 输出:OUTPUT_CALLBACK → SampleReceiver.onEncoded → toH264Frame → [onFrame] 回调。
 *
 * onFrame 里 controller 负责:
 *   - `_frames.tryEmit(frame)` 广播给所有 EncodingHandle.frames 订阅者
 *   - `IosRecordingFrameBridge.onVideoFrame(frame)` 转发给 IosRecordingService(v1.2 契约保留)
 *
 * 生命周期:
 *   - [start]:VTCompressionSessionCreate + wire OUTPUT_CALLBACK refCon。失败 return false。
 *   - [encodeSample]:同步 encode 单帧,forceKey 时传 frameProperties 字典。
 *   - [invalidate]:VTCompressionSessionInvalidate + CFRelease + StableRef.dispose。幂等。
 */
@OptIn(ExperimentalForeignApi::class)
internal class EncodingSession(
    private val config: CaptureConfig,
    private val onFrame: (H264Frame) -> Unit,
) {
    @Volatile
    private var compressionSession: VTCompressionSessionRef? = null

    @Volatile
    private var receiverRef: COpaquePointer? = null

    @Volatile
    private var invalidated: Boolean = false

    /**
     * 建 VT session + wire OUTPUT_CALLBACK。成功 return true,失败 return false 并已 emit error log。
     */
    fun start(): Boolean {
        if (compressionSession != null) return true
        if (invalidated) return false

        val receiver = SampleReceiver(onFrame)
        val stableRef = StableRef.create(receiver)
        val refConPtr = stableRef.asCPointer()

        val session = memScoped {
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
                outputCallbackRefCon = refConPtr,
                compressionSessionOut = out.ptr,
            )
            if (status != 0) null else out.value
        }

        if (session == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_ENCODING_SESSION_CREATE_FAIL VT status non-zero"
            )
            stableRef.dispose()
            return false
        }
        compressionSession = session
        configureSession(session)
        receiverRef = refConPtr
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_ENCODING_SESSION_START ${config.widthPx}x${config.heightPx}@${config.frameRate} " +
                "codec=H264"
        )
        return true
    }

    /**
     * Encode single CMSampleBuffer(delegate 每帧调用)。
     * `forceKey=true` 时构造 frameProperties = { kVTEncodeFrameOptionKey_ForceKeyFrame: true }。
     */
    fun encodeSample(sample: CMSampleBufferRef, forceKey: Boolean) {
        val session = compressionSession ?: return
        val imageBuffer = CMSampleBufferGetImageBuffer(sample) ?: return
        val pts = CMSampleBufferGetPresentationTimeStamp(sample)
        val duration = CMTimeMake(value = 1L, timescale = config.frameRate)

        if (forceKey) {
            val dict = CFDictionaryCreateMutable(
                allocator = kCFAllocatorDefault,
                capacity = 1L,
                keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
                valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
            )
            if (dict != null) {
                CFDictionarySetValue(
                    theDict = dict,
                    key = kVTEncodeFrameOptionKey_ForceKeyFrame,
                    value = kCFBooleanTrue,
                )
                try {
                    VTCompressionSessionEncodeFrame(
                        session = session,
                        imageBuffer = imageBuffer,
                        presentationTimeStamp = pts,
                        duration = duration,
                        frameProperties = dict.reinterpret(),
                        sourceFrameRefcon = null,
                        infoFlagsOut = null,
                    )
                } finally {
                    CFRelease(dict)
                }
            } else {
                // fallback: encode without force-key rather than stall the pipeline
                VTCompressionSessionEncodeFrame(
                    session = session,
                    imageBuffer = imageBuffer,
                    presentationTimeStamp = pts,
                    duration = duration,
                    frameProperties = null,
                    sourceFrameRefcon = null,
                    infoFlagsOut = null,
                )
            }
        } else {
            VTCompressionSessionEncodeFrame(
                session = session,
                imageBuffer = imageBuffer,
                presentationTimeStamp = pts,
                duration = duration,
                frameProperties = null,
                sourceFrameRefcon = null,
                infoFlagsOut = null,
            )
        }
    }

    /**
     * Invalidate + CFRelease VT session,dispose StableRef。幂等(第二次 no-op)。
     * plan Q6:降级时完全释放,不 pause。
     */
    fun invalidate() {
        if (invalidated) return
        invalidated = true
        compressionSession?.let {
            VTCompressionSessionInvalidate(it)
            CFRelease(it)
        }
        compressionSession = null
        receiverRef?.asStableRef<SampleReceiver>()?.dispose()
        receiverRef = null
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_ENCODING_SESSION_INVALIDATE"
        )
    }

    private fun configureSession(session: VTCompressionSessionRef) {
        setBooleanProperty(
            session = session,
            key = kVTCompressionPropertyKey_RealTime,
            value = kCFBooleanTrue,
            keyName = "RealTime",
        )
        setBooleanProperty(
            session = session,
            key = kVTCompressionPropertyKey_AllowFrameReordering,
            value = kCFBooleanFalse,
            keyName = "AllowFrameReordering",
        )
    }

    private fun setBooleanProperty(
        session: VTCompressionSessionRef,
        key: CPointer<__CFString>?,
        value: CPointer<out CPointed>?,
        keyName: String,
    ) {
        val cfKey = key ?: return
        val cfValue = value ?: return
        val status = VTSessionSetProperty(
            session = session,
            propertyKey = cfKey,
            propertyValue = cfValue,
        )
        if (status != 0) {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Media,
                "IOS_ENCODING_SESSION_SET_PROPERTY_FAIL key=$keyName status=$status",
            )
        } else {
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_ENCODING_SESSION_SET_PROPERTY_OK key=$keyName",
            )
        }
    }

    // =========================================================
    // Bridge classes
    // =========================================================

    /**
     * StableRef 承载的 receiver。VideoToolbox OUTPUT_CALLBACK 在 encode 线程回调,通过
     * refCon 恢复 [SampleReceiver] 并把 CMSampleBuffer 转 [H264Frame] 通过 [onFrame] 派发。
     */
    private class SampleReceiver(private val onFrame: (H264Frame) -> Unit) {
        fun onEncoded(sample: CMSampleBufferRef) {
            val frame = toH264Frame(sample) ?: return
            onFrame(frame)
        }
    }

    companion object {
        /**
         * VideoToolbox 静态桥。签名匹配 `VTCompressionOutputCallback`:
         *   (void*, void*, OSStatus, VTEncodeInfoFlags, CMSampleBufferRef?) -> Unit
         */
        private val OUTPUT_CALLBACK = staticCFunction<
            COpaquePointer?,
            COpaquePointer?,
            Int,
            UInt,
            CMSampleBufferRef?,
            Unit,
        > { refCon, _, status, _, sampleBuffer ->
            if (status != 0 || sampleBuffer == null || refCon == null) return@staticCFunction
            val receiver = refCon.asStableRef<SampleReceiver>().get()
            receiver.onEncoded(sampleBuffer)
        }

        /**
         * v1.2 IosCameraStreamer.toH264Frame 逻辑搬迁:
         *   1. 提 PTS
         *   2. 判断 key frame
         *   3. 若 key frame:从 formatDescription 提取 SPS + PPS
         *   4. Split AVCC data buffer → NAL 列表
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
                codec = VideoCodec.H264,
            )
        }

        /**
         * VideoToolbox 通过 sample 的 attachments 数组标注 NotSync。为 muxer 兼容,
         * 默认按 key frame 处理(over-count 40 字节/帧的 SPS/PPS 开销可接受)。
         */
        private fun isKeyFrame(sample: CMSampleBufferRef): Boolean {
            val attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary = false)
                ?: return true
            // v1.2 T-P2-2 保留 v1.2 相同行为:优先 SPS/PPS,不细分 NotSync
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
    }
}

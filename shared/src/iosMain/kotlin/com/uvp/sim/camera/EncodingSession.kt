package com.uvp.sim.camera

import cnames.structs.__CFString
import com.uvp.sim.api.LogTag
import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.MediaTimebase
import com.uvp.sim.media.H265NalType
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
import platform.CoreMedia.CMVideoFormatDescriptionGetHEVCParameterSetAtIndex
import platform.CoreMedia.kCMVideoCodecType_H264
import platform.CoreMedia.kCMVideoCodecType_HEVC
import platform.VideoToolbox.VTCompressionSessionCreate
import platform.VideoToolbox.VTCompressionSessionEncodeFrame
import platform.VideoToolbox.VTCompressionSessionInvalidate
import platform.VideoToolbox.VTCompressionSessionRef
import platform.VideoToolbox.VTCompressionSessionRefVar
import platform.VideoToolbox.VTSessionSetProperty
import platform.VideoToolbox.kVTEncodeFrameOptionKey_ForceKeyFrame
import platform.VideoToolbox.kVTCompressionPropertyKey_AllowFrameReordering
import platform.VideoToolbox.kVTCompressionPropertyKey_ProfileLevel
import platform.VideoToolbox.kVTCompressionPropertyKey_RealTime
import platform.VideoToolbox.kVTProfileLevel_HEVC_Main_AutoLevel
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
    /**
     * T-B1-1:暴露 codec 只读字段,方便 test 与 SampleReceiver 分支使用。
     * 单一真源:从 config.videoCodec 读,不再单独接受构造参数(plan §3.1 决策)。
     */
    val videoCodec: VideoCodec get() = config.videoCodec

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

        val receiver = SampleReceiver(config.videoCodec, onFrame)
        val stableRef = StableRef.create(receiver)
        val refConPtr = stableRef.asCPointer()

        // T-B1-2:VTCompressionSession codecType 按 config.videoCodec 分支
        val codecType = when (config.videoCodec) {
            VideoCodec.H264 -> kCMVideoCodecType_H264
            VideoCodec.H265 -> kCMVideoCodecType_HEVC
        }

        val session = memScoped {
            val out = alloc<VTCompressionSessionRefVar>()
            val status = VTCompressionSessionCreate(
                allocator = null,
                width = config.widthPx,
                height = config.heightPx,
                codecType = codecType,
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
                "IOS_ENCODING_SESSION_CREATE_FAIL VT status non-zero codec=${config.videoCodec.label}"
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
                "codec=${config.videoCodec.label}"
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
        // T-B1-3:HEVC 分支加 ProfileLevel = HEVC_Main_AutoLevel(H.264 保持系统默认 baseline-ish)
        if (config.videoCodec == VideoCodec.H265) {
            val profileLevel = kVTProfileLevel_HEVC_Main_AutoLevel
            if (profileLevel != null) {
                setCFTypeProperty(
                    session = session,
                    key = kVTCompressionPropertyKey_ProfileLevel,
                    value = profileLevel,
                    keyName = "ProfileLevel=HEVC_Main_AutoLevel",
                )
            } else {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "IOS_ENCODING_SESSION_HEVC_PROFILE_LEVEL_NULL fallback to encoder default",
                )
            }
        }
    }

    /**
     * T-B1-3:CFType 属性(如 profile level 是 CFStringRef)通用 setter。
     * 与 `setBooleanProperty` 分开是因为后者的 value 类型是 kCFBooleanTrue/False 常量指针,
     * 这里的 value 是 CFType(CFStringRef 等)。
     */
    private fun setCFTypeProperty(
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
                LogLevel.Warning, LogTag.Media,
                "IOS_ENCODING_SESSION_SET_PROPERTY_FAIL key=$keyName status=$status",
            )
        } else {
            SystemLogger.emit(
                LogLevel.Debug, LogTag.Media,
                "IOS_ENCODING_SESSION_SET_PROPERTY_OK key=$keyName",
            )
        }
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
    private class SampleReceiver(
        private val codec: VideoCodec,
        private val onFrame: (H264Frame) -> Unit,
    ) {
        fun onEncoded(sample: CMSampleBufferRef) {
            val frame = toEncodedFrame(sample, codec) ?: return
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
         * T-B1-4:编解码 codec 分派入口。当前 SampleReceiver.onEncoded 直接调这里,
         * 根据 codec 走 H.264 / H.265 分支。
         */
        internal fun toEncodedFrame(sample: CMSampleBufferRef, codec: VideoCodec): H264Frame? =
            when (codec) {
                VideoCodec.H264 -> toH264Frame(sample)
                VideoCodec.H265 -> toH265Frame(sample)
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
                    extractH264ParameterSet(formatDesc, index = 0uL)?.let { sps ->
                        if (sps[0].toInt() and 0x1F == NalType.SPS) nals += sps
                    }
                    extractH264ParameterSet(formatDesc, index = 1uL)?.let { pps ->
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
         * T-B1-4:HEVC 版本的 toH264Frame。
         *
         *   1. 提 PTS
         *   2. 判断 key frame(HEVC IDR_W_RADL / IDR_N_LP / CRA_NUT)
         *   3. 若 key frame:从 formatDescription 提取 VPS + SPS + PPS(index 0 / 1 / 2)
         *   4. Split AVCC(4B 长度前缀,spike 结论 T-B1-0)→ NAL 列表
         *
         * 与 H.264 差异:
         *   - 参数集数量从 2(SPS/PPS)变 3(VPS/SPS/PPS)
         *   - NAL type 从低 5 位 → `(byte0 >> 1) & 0x3F`
         *   - key frame 由多种 NAL type 组合判断
         */
        internal fun toH265Frame(sample: CMSampleBufferRef): H264Frame? = memScoped {
            val ptsCmTime = CMSampleBufferGetPresentationTimeStamp(sample)
            val ptsUs = MediaTimebase.cmTimeToMicros(ptsCmTime)
            val nals = mutableListOf<ByteArray>()

            val blockBuffer = CMSampleBufferGetDataBuffer(sample) ?: return@memScoped null
            val avccBytes = readBlockBuffer(blockBuffer) ?: return@memScoped null
            val bodyNals = AnnexB.splitAvcc(avccBytes, lengthPrefixSize = 4)

            // 检测 body 里是否含 HEVC IDR/CRA slice —— 若含,再拉参数集
            val isKey = bodyNals.any { nal ->
                if (nal.isEmpty()) return@any false
                val t = (nal[0].toInt() ushr 1) and 0x3F
                VideoCodec.H265.isKeyNal(t)
            }

            if (isKey) {
                val formatDesc = CMSampleBufferGetFormatDescription(sample)
                if (formatDesc != null) {
                    // VPS/SPS/PPS 按 index 0/1/2 顺序
                    extractHevcParameterSet(formatDesc, index = 0uL)?.let { vps ->
                        if (vps.isNotEmpty() &&
                            ((vps[0].toInt() ushr 1) and 0x3F) == H265NalType.VPS_NUT
                        ) {
                            nals += vps
                        }
                    }
                    extractHevcParameterSet(formatDesc, index = 1uL)?.let { sps ->
                        if (sps.isNotEmpty() &&
                            ((sps[0].toInt() ushr 1) and 0x3F) == H265NalType.SPS_NUT
                        ) {
                            nals += sps
                        }
                    }
                    extractHevcParameterSet(formatDesc, index = 2uL)?.let { pps ->
                        if (pps.isNotEmpty() &&
                            ((pps[0].toInt() ushr 1) and 0x3F) == H265NalType.PPS_NUT
                        ) {
                            nals += pps
                        }
                    }
                }
            }

            nals += bodyNals
            if (nals.isEmpty()) return@memScoped null
            H264Frame(
                nalUnits = nals,
                timestampUs = ptsUs,
                isKeyFrame = isKey,
                codec = VideoCodec.H265,
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

        private fun extractH264ParameterSet(
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

        /**
         * T-B1-4:HEVC 参数集读取。iOS 11+ CoreMedia 才有
         * `CMVideoFormatDescriptionGetHEVCParameterSetAtIndex`。K/N 侧 platform.CoreMedia
         * 已把该符号带出来。VPS / SPS / PPS 分别按 index 0 / 1 / 2 拉。
         */
        private fun extractHevcParameterSet(
            formatDesc: platform.CoreMedia.CMFormatDescriptionRef,
            index: ULong,
        ): ByteArray? = memScoped {
            val sizeOut = alloc<size_tVar>()
            val dataPtrOut = alloc<CPointerVar<UByteVar>>()
            val status = CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
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

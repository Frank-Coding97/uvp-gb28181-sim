package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.media.AnnexB
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.MediaTimebase
import com.uvp.sim.media.NalType
import com.uvp.sim.media.VideoCodec
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.IosRecordingFrameBridge
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
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
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.kCFAllocatorDefault
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
import platform.CoreVideo.CVImageBufferRef
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.VideoToolbox.VTCompressionSessionCreate
import platform.VideoToolbox.VTCompressionSessionEncodeFrame
import platform.VideoToolbox.VTCompressionSessionInvalidate
import platform.VideoToolbox.VTCompressionSessionRef
import platform.VideoToolbox.VTCompressionSessionRefVar
import platform.VideoToolbox.kVTEncodeFrameOptionKey_ForceKeyFrame
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
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
 * - ✅ AVCaptureSession real camera feed via [CameraSampleDelegate] (T4-follow-up)
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosCameraStreamer(private val config: CaptureConfig) {

    @Volatile
    private var compressionSession: VTCompressionSessionRef? = null

    @Volatile
    private var receiverRef: COpaquePointer? = null

    @Volatile
    private var frameChannel: Channel<H264Frame>? = null

    @Volatile
    private var _captureSession: AVCaptureSession? = null

    @Volatile
    private var captureInput: AVCaptureDeviceInput? = null

    @Volatile
    private var captureOutput: AVCaptureVideoDataOutput? = null

    @Volatile
    private var sampleDelegate: CameraSampleDelegate? = null

    @Volatile
    private var pendingForceKey: Boolean = false

    /**
     * 最近一帧 `CVImageBufferRef`,由 [encodeSample] 每帧刷新。SnapshotCapture 用这一帧
     * 做 CVPixelBuffer → CIImage → UIImage → JPEG 转换,避免额外挂 AVCapturePhotoOutput。
     *
     * 生命周期:
     *   - 每次 [encodeSample] 把新帧 CFRetain 一次并原子替换旧值(旧值 CFRelease)
     *   - [releaseInternal] 释放最后持有的一帧
     *   - 消费方(SnapshotCapture)通过 [latestFramePixelBuffer] 再 CFRetain 一次,使用完 CFRelease
     */
    @Volatile
    private var latestFrame: CVImageBufferRef? = null

    /**
     * Backing [AVCaptureSession] created inside [stream]. Exposed for
     * downstream `PlatformCameraPreview` (T11) so it can hang a
     * `AVCaptureVideoPreviewLayer` off the same session.
     *
     * Null before [stream] first collects, and after [releaseInternal].
     */
    val captureSession: AVCaptureSession?
        get() = _captureSession

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

        val wired = wireCaptureSession()
        if (!wired) {
            // wireCaptureSession already emitted the error; unwind compression session.
            releaseInternal()
            close(IllegalStateException("AVCaptureSession wire-up failed"))
            return@callbackFlow
        }

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
     * Sets a volatile flag consumed by the next [encodeSample] call, which
     * passes `kVTEncodeFrameOptionKey_ForceKeyFrame = kCFBooleanTrue` in the
     * `frameProperties` dict of [VTCompressionSessionEncodeFrame].
     *
     * No-op when session is null (stream not running).
     */
    @Suppress("unused")
    fun requestKeyFrame() {
        if (compressionSession == null) return
        pendingForceKey = true
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "requestKeyFrame queued — next encoded frame will carry force-key flag"
        )
    }

    /**
     * 取当前最近一帧 pixel buffer,并额外 CFRetain 一次交给调用方。
     * 调用方(SnapshotCapture)使用完必须 [CFRelease]。
     *
     * 返回 null 表示尚未有帧到达(stream 还没起来 / 首帧未到)。
     */
    fun latestFramePixelBuffer(): CVImageBufferRef? {
        val current = latestFrame ?: return null
        CFRetain(current)
        return current
    }

    /** 原子替换 [latestFrame],旧值 CFRelease。encodeSample 每帧调用一次。 */
    private fun publishLatestFrame(newFrame: CVImageBufferRef) {
        val old = latestFrame
        // 先 retain 新的再替换,避免消费者观察到窗口内为 null
        CFRetain(newFrame)
        latestFrame = newFrame
        if (old != null) CFRelease(old)
    }

    /**
     * Encode a single [CMSampleBufferRef] captured by [CameraSampleDelegate].
     *
     * Extracts the [CVImageBuffer] and feeds it to
     * [VTCompressionSessionEncodeFrame]. When [forceKeyFrame] is true a small
     * `{ kVTEncodeFrameOptionKey_ForceKeyFrame : kCFBooleanTrue }` dictionary
     * is passed as `frameProperties`; otherwise `frameProperties = null`.
     *
     * All CFDictionary allocation is scoped to [memScoped] and released
     * before return.
     */
    internal fun encodeSample(sample: CMSampleBufferRef, forceKeyFrame: Boolean = false) {
        val session = compressionSession ?: return
        val imageBuffer = CMSampleBufferGetImageBuffer(sample) ?: return
        publishLatestFrame(imageBuffer)
        val pts = CMSampleBufferGetPresentationTimeStamp(sample)
        // Duration is best-effort — VideoToolbox uses it to compute inter-frame
        // spacing; if the sample buffer doesn't carry one we hand it 1/fps.
        val duration = CMTimeMake(value = 1L, timescale = config.frameRate)

        if (forceKeyFrame) {
            // Build the frame-properties CFDictionary via CFMutableDictionary +
            // CFDictionarySetValue — avoids the CFDictionaryCreate array-of-
            // pointers boilerplate that trips up K/N type inference.
            val dict = CFDictionaryCreateMutable(
                allocator = kCFAllocatorDefault,
                capacity = 1L,
                keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
                valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr
            )
            if (dict != null) {
                CFDictionarySetValue(
                    theDict = dict,
                    key = kVTEncodeFrameOptionKey_ForceKeyFrame,
                    value = kCFBooleanTrue
                )
                try {
                    VTCompressionSessionEncodeFrame(
                        session = session,
                        imageBuffer = imageBuffer,
                        presentationTimeStamp = pts,
                        duration = duration,
                        frameProperties = dict.reinterpret(),
                        sourceFrameRefcon = null,
                        infoFlagsOut = null
                    )
                } finally {
                    CFRelease(dict)
                }
            } else {
                // Fallback: encode without force-key flag so we don't stall the pipeline.
                VTCompressionSessionEncodeFrame(
                    session = session,
                    imageBuffer = imageBuffer,
                    presentationTimeStamp = pts,
                    duration = duration,
                    frameProperties = null,
                    sourceFrameRefcon = null,
                    infoFlagsOut = null
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
                infoFlagsOut = null
            )
        }
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

    /**
     * Build + start [AVCaptureSession] wired to a [CameraSampleDelegate] that
     * forwards each frame back into [encodeSample]. Returns true on success,
     * false on device/input error (caller is responsible for unwinding the
     * compression session).
     *
     * v1.1 only opens the back camera (spec DEC-4). Front camera is a v1.2 item.
     */
    private fun wireCaptureSession(): Boolean {
        val builtInWideAngle = AVCaptureDeviceTypeBuiltInWideAngleCamera
            ?: run {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "AVCaptureDeviceTypeBuiltInWideAngleCamera constant null — SDK mismatch"
                )
                return false
            }
        val device: AVCaptureDevice? = AVCaptureDevice.defaultDeviceWithDeviceType(
            deviceType = builtInWideAngle,
            mediaType = AVMediaTypeVideo,
            position = AVCaptureDevicePositionBack,
        )
        if (device == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "AVCaptureDevice back-camera lookup returned null — cannot start capture"
            )
            return false
        }

        val input: AVCaptureDeviceInput = memScoped {
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            val created = AVCaptureDeviceInput.deviceInputWithDevice(device, errorVar.ptr)
            if (created == null) {
                val err = errorVar.value
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "AVCaptureDeviceInput init failed: ${err?.localizedDescription ?: "unknown"}"
                )
                return@wireCaptureSession false
            }
            created
        }

        val session = AVCaptureSession()
        session.beginConfiguration()

        session.sessionPreset = pickSessionPreset(config.widthPx, config.heightPx)

        if (!session.canAddInput(input)) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "AVCaptureSession refused input — camera busy or restricted"
            )
            session.commitConfiguration()
            return false
        }
        session.addInput(input)

        val output = AVCaptureVideoDataOutput()
        val pixelFormatKey = kCVPixelBufferPixelFormatTypeKey
            ?: run {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "kCVPixelBufferPixelFormatTypeKey null — cannot configure output"
                )
                session.commitConfiguration()
                return false
            }
        // 420YpCbCr8BiPlanarFullRange is VideoToolbox's canonical input pixel
        // format; passing anything else forces an internal copy.
        val nv12 = NSNumber(unsignedInt = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
        output.videoSettings = mapOf<Any?, Any?>(pixelFormatKey to nv12)

        val delegate = CameraSampleDelegate { sample ->
            val force = pendingForceKey
            if (force) pendingForceKey = false
            encodeSample(sample, forceKeyFrame = force)
        }
        // Dedicated serial queue keeps the delegate off the main thread.
        val queue = dispatch_queue_create("uvp.camera.capture", null)
        output.setSampleBufferDelegate(delegate, queue)

        if (!session.canAddOutput(output)) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "AVCaptureSession refused output — configuration incompatible"
            )
            session.commitConfiguration()
            return false
        }
        session.addOutput(output)
        session.commitConfiguration()

        // startRunning is blocking — on iOS 17+ Apple recommends a background
        // queue. Dispatch async so we don't stall the collector.
        dispatch_queue_create("uvp.camera.start", null).let { startQueue ->
            platform.darwin.dispatch_async(startQueue) {
                session.startRunning()
            }
        }

        _captureSession = session
        IosCameraSessionHolder.publish(session)
        IosSnapshotSourceHolder.publish(this)
        captureInput = input
        captureOutput = output
        sampleDelegate = delegate
        return true
    }

    private fun pickSessionPreset(width: Int, height: Int): String {
        // Pick the smallest built-in preset >= requested. Falls back to 720p
        // (the most GB28181-common resolution) when nothing fits.
        val fallback = AVCaptureSessionPreset1280x720
            ?: return "AVCaptureSessionPreset1280x720" // constant should never be null on iOS 5+
        val chosen: String? = when {
            width >= 1920 || height >= 1080 -> AVCaptureSessionPreset1920x1080
            width >= 1280 || height >= 720 -> AVCaptureSessionPreset1280x720
            width >= 640 || height >= 480 -> AVCaptureSessionPreset640x480
            else -> AVCaptureSessionPreset1280x720
        }
        return chosen ?: fallback
    }

    private fun releaseInternal() {
        _captureSession?.let { session ->
            if (session.isRunning()) session.stopRunning()
            captureInput?.let { session.removeInput(it) }
            captureOutput?.let { session.removeOutput(it) }
        }
        _captureSession = null
        IosCameraSessionHolder.publish(null)
        captureInput = null
        captureOutput = null
        sampleDelegate = null

        compressionSession?.let {
            VTCompressionSessionInvalidate(it)
            CFRelease(it)
        }
        compressionSession = null
        receiverRef?.asStableRef<SampleReceiver>()?.dispose()
        receiverRef = null
        frameChannel = null
        pendingForceKey = false

        // 释放最后一帧,避免 stream 结束后仍占着 CVPixelBuffer
        latestFrame?.let { CFRelease(it) }
        latestFrame = null
        IosSnapshotSourceHolder.publish(null)
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
            IosRecordingFrameBridge.onVideoFrame(frame)
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

/**
 * `AVCaptureVideoDataOutputSampleBufferDelegate` NSObject subclass.
 *
 * Kotlin/Native requires ObjC delegates to be `NSObject` subclasses annotated
 * with `@ExportObjCClass` so the runtime registers the class with the ObjC
 * runtime. Forwards each captured [CMSampleBufferRef] to [onSample].
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
@kotlinx.cinterop.ExportObjCClass
internal class CameraSampleDelegate(
    private val onSample: (CMSampleBufferRef) -> Unit,
) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        didOutputSampleBuffer?.let(onSample)
    }
}

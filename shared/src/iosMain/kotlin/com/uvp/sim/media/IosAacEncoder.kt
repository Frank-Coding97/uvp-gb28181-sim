package com.uvp.sim.media

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.AudioToolbox.AudioConverterDispose
import platform.AudioToolbox.AudioConverterFillComplexBuffer
import platform.AudioToolbox.AudioConverterGetProperty
import platform.AudioToolbox.AudioConverterGetPropertyInfo
import platform.AudioToolbox.AudioConverterNew
import platform.AudioToolbox.AudioConverterRef
import platform.AudioToolbox.AudioConverterRefVar
import platform.AudioToolbox.kAudioConverterPropertyMaximumOutputPacketSize
import platform.AudioToolbox.kAudioConverterCompressionMagicCookie
import platform.CoreAudioTypes.AudioBuffer
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioStreamPacketDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC

/**
 * T-B2-1 / T-B2-2 / T-B2-3:iOS AAC LC 硬编器,封装 CoreAudio 低层
 * `AudioConverter` API。
 *
 * 输入:PCM 44.1 kHz 单声道 16-bit,通过 [encode] 一段一段喂进。
 * 输出:List<AudioFrame(codec = AAC)>,每帧 payload = 7 字节 ADTS 头 + raw AAC frame。
 *
 * 内部:
 *   - 环形累积 buffer(累计到 1024 samples 触发一次 AudioConverterFillComplexBuffer)
 *   - pull-based input callback 从 ring buffer 拉数据(staticCFunction 桥接)
 *
 * 线程模型:调用方从单一 AVAudioEngine tap dispatch queue 调 [encode],[close];
 * 非线程安全 —— 不要跨线程调 encode。
 *
 * 生命周期:构造 → 首次 encode 时 lazy init AudioConverter → [close] 显式释放。
 */
@OptIn(ExperimentalForeignApi::class)
class IosAacEncoder(
    private val pcmSampleRateHz: Double = 44_100.0,
    private val channelCount: UInt = 1u,
    private val aacSampleRateHz: Double = 44_100.0,
) {

    /** AAC LC 一帧固定 1024 samples(编码器输出粒度)。 */
    private val samplesPerFrame: Int = 1024

    /** PCM 16-bit interleaved 单帧字节数 = 2 * channels。 */
    private val bytesPerSample: Int = 2 * channelCount.toInt()

    private var converter: AudioConverterRef? = null
    private var maxOutputPacketSize: Int = 4096
    private var callbackContextRef: StableRef<CallbackContext>? = null

    /** 累计到 samplesPerFrame 后 emit 一帧。相邻 encode 之间跨越 emit 需要 buffer。 */
    private val pcmBuffer: ArrayDeque<Short> = ArrayDeque(samplesPerFrame * 4)

    /** 已 emit 的 sample 数(用于 timestamp 递推)。 */
    private var emittedSamples: Long = 0L

    /** 起始 timestamp(第一次 encode 的入参 timestampUs);后续用它 + emittedSamples 派生。 */
    private var startTsUs: Long = -1L

    private var closed: Boolean = false

    /**
     * T-B2-2:喂 PCM,累计够一帧就走 AudioConverterFillComplexBuffer,可能 emit 0/1/N 帧。
     *
     * @param pcm 16-bit interleaved PCM 数据(单声道 = 每样本 1 short)
     * @param timestampUs 本 chunk 起始时间戳(仅用于第一次 emit;后续用累积 sample 数派生)
     */
    fun encode(pcm: ShortArray, timestampUs: Long): List<AudioFrame> {
        if (closed) return emptyList()
        if (startTsUs < 0) startTsUs = timestampUs

        // 累积
        for (s in pcm) pcmBuffer.addLast(s)

        val out = mutableListOf<AudioFrame>()
        while (pcmBuffer.size >= samplesPerFrame) {
            val frameBytes = encodeOneFrame() ?: break
            val frameTsUs = startTsUs + emittedSamples * 1_000_000L / aacSampleRateHz.toLong()
            val adts = AdtsHeader.wrap(
                aacFrame = frameBytes,
                sampleRateHz = aacSampleRateHz.toInt(),
                channels = channelCount.toInt(),
            )
            out += AudioFrame(payload = adts, timestampUs = frameTsUs, codec = AudioCodec.AAC)
            emittedSamples += samplesPerFrame
        }
        return out
    }

    private fun encodeOneFrame(): ByteArray? {
        val conv = converter ?: run {
            val c = initConverter() ?: return null
            converter = c
            c
        }

        // 从 pcmBuffer 拉 samplesPerFrame 到临时 ShortArray → context ready for callback
        val chunk = ShortArray(samplesPerFrame) {
            pcmBuffer.removeFirst()
        }
        val ctx = callbackContextRef!!.get()
        ctx.setPcmChunk(chunk)

        return memScoped {
            val bufferList = alloc<AudioBufferList>()
            bufferList.mNumberBuffers = 1u
            val outBufBytes = nativeHeap.allocArray<UByteVar>(maxOutputPacketSize)
            try {
                bufferList.mBuffers[0].apply {
                    mNumberChannels = channelCount
                    mDataByteSize = maxOutputPacketSize.convert()
                    mData = outBufBytes.reinterpret()
                }
                val outPacketDesc = alloc<AudioStreamPacketDescription>()
                val ioOutputDataPackets = alloc<UIntVar>().apply { value = 1u }

                val status = AudioConverterFillComplexBuffer(
                    inAudioConverter = conv,
                    inInputDataProc = INPUT_CALLBACK,
                    inInputDataProcUserData = callbackContextRef!!.asCPointer(),
                    ioOutputDataPacketSize = ioOutputDataPackets.ptr,
                    outOutputData = bufferList.ptr,
                    outPacketDescription = outPacketDesc.ptr,
                )
                if (status != 0 && status != 1) {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "IOS_AAC_FILL_BUFFER_FAIL status=$status",
                    )
                    return@memScoped null
                }
                val outSize = bufferList.mBuffers[0].mDataByteSize.toInt()
                if (outSize <= 0) return@memScoped null
                outBufBytes.readBytes(outSize)
            } finally {
                nativeHeap.free(outBufBytes.rawValue)
            }
        }
    }

    private fun initConverter(): AudioConverterRef? = memScoped {
        val pcmAsbd = alloc<AudioStreamBasicDescription>().apply {
            mSampleRate = pcmSampleRateHz
            mFormatID = kAudioFormatLinearPCM
            mFormatFlags = (kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked).convert()
            mBytesPerPacket = bytesPerSample.convert()
            mFramesPerPacket = 1u
            mBytesPerFrame = bytesPerSample.convert()
            mChannelsPerFrame = channelCount
            mBitsPerChannel = 16u
            mReserved = 0u
        }
        val aacAsbd = alloc<AudioStreamBasicDescription>().apply {
            mSampleRate = aacSampleRateHz
            mFormatID = kAudioFormatMPEG4AAC
            mFormatFlags = 0u
            mBytesPerPacket = 0u
            mFramesPerPacket = samplesPerFrame.convert()
            mBytesPerFrame = 0u
            mChannelsPerFrame = channelCount
            mBitsPerChannel = 0u
            mReserved = 0u
        }
        val out = alloc<AudioConverterRefVar>()
        val status = AudioConverterNew(pcmAsbd.ptr, aacAsbd.ptr, out.ptr)
        if (status != 0 || out.value == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_AAC_CONVERTER_NEW_FAIL status=$status",
            )
            return@memScoped null
        }
        val conv = out.value!!

        // 查最大输出 packet size
        memScoped {
            val sizeVar = alloc<UIntVar>().apply { value = 4u }
            val maxSize = alloc<UIntVar>()
            val s = AudioConverterGetProperty(
                conv,
                kAudioConverterPropertyMaximumOutputPacketSize,
                sizeVar.ptr,
                maxSize.ptr,
            )
            if (s == 0) maxOutputPacketSize = maxSize.value.toInt().coerceAtLeast(1024)
        }

        // context stable ref
        callbackContextRef = StableRef.create(
            CallbackContext(bytesPerSample = bytesPerSample, channels = channelCount)
        )
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_AAC_CONVERTER_INIT sr=${aacSampleRateHz.toInt()} ch=${channelCount.toInt()} " +
                "maxPkt=$maxOutputPacketSize",
        )
        conv
    }

    /**
     * 取 AudioConverter MagicCookie 前 2 字节 → AudioSpecificConfig(用于 SDP fmtp)。
     * 若 converter 未初始化或 cookie 不可用返回 null。
     */
    fun audioSpecificConfig(): ByteArray? {
        val conv = converter ?: return null
        return memScoped {
            val sizeVar = alloc<UIntVar>()
            val writableVar = alloc<kotlinx.cinterop.BooleanVar>()
            var status = AudioConverterGetPropertyInfo(
                conv,
                kAudioConverterCompressionMagicCookie,
                sizeVar.ptr,
                writableVar.ptr,
            )
            if (status != 0 || sizeVar.value.toInt() <= 0) return@memScoped null
            val size = sizeVar.value.toInt()
            val bytes = nativeHeap.allocArray<UByteVar>(size)
            try {
                val outSize = alloc<UIntVar>().apply { value = size.convert() }
                status = AudioConverterGetProperty(
                    conv,
                    kAudioConverterCompressionMagicCookie,
                    outSize.ptr,
                    bytes,
                )
                if (status != 0) return@memScoped null
                val readSize = outSize.value.toInt().coerceAtMost(size)
                if (readSize < 2) return@memScoped null
                // MagicCookie 通常是 完整 MP4 ES descriptor;前 2 字节即 AudioSpecificConfig
                val full = bytes.readBytes(readSize)
                // 尝试从中定位 AudioSpecificConfig(简化:直接返回前 2 字节 —— plan §3.2.2 决策)
                byteArrayOf(full[0], full[1])
            } finally {
                nativeHeap.free(bytes.rawValue)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        converter?.let {
            AudioConverterDispose(it)
        }
        converter = null
        callbackContextRef?.dispose()
        callbackContextRef = null
        pcmBuffer.clear()
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_AAC_CONVERTER_CLOSED")
    }

    /**
     * AudioConverter input callback 上下文。持有当前 1024-sample PCM chunk 的 native
     * 内存副本,callback 回来时把指针指向它。
     */
    private class CallbackContext(
        val bytesPerSample: Int,
        val channels: UInt,
    ) {
        @OptIn(ExperimentalForeignApi::class)
        private var pcmBufPtr: CPointer<UByteVar>? = null
        private var pcmBufSize: Int = 0
        private var served: Boolean = false

        @OptIn(ExperimentalForeignApi::class)
        fun setPcmChunk(chunk: ShortArray) {
            // 释放上一个
            pcmBufPtr?.let { nativeHeap.free(it.rawValue) }
            val bytes = chunk.size * 2
            val ptr = nativeHeap.allocArray<UByteVar>(bytes)
            for (i in chunk.indices) {
                val v = chunk[i].toInt()
                ptr[i * 2] = (v and 0xFF).toUByte()
                ptr[i * 2 + 1] = ((v ushr 8) and 0xFF).toUByte()
            }
            pcmBufPtr = ptr
            pcmBufSize = bytes
            served = false
        }

        @OptIn(ExperimentalForeignApi::class)
        fun fill(
            ioNumberDataPackets: CPointer<UIntVar>,
            ioData: CPointer<AudioBufferList>,
        ): Int {
            if (served) {
                ioNumberDataPackets.pointed.value = 0u
                return 0
            }
            val ptr = pcmBufPtr ?: run {
                ioNumberDataPackets.pointed.value = 0u
                return 0
            }
            val bufList = ioData.pointed
            bufList.mNumberBuffers = 1u
            bufList.mBuffers[0].apply {
                mNumberChannels = channels
                mDataByteSize = pcmBufSize.convert()
                mData = ptr.reinterpret()
            }
            val numPackets = pcmBufSize / bytesPerSample
            ioNumberDataPackets.pointed.value = numPackets.convert()
            served = true
            return 0
        }

        @OptIn(ExperimentalForeignApi::class)
        fun release() {
            pcmBufPtr?.let { nativeHeap.free(it.rawValue) }
            pcmBufPtr = null
        }
    }

    private companion object {
        @OptIn(ExperimentalForeignApi::class)
        val INPUT_CALLBACK = staticCFunction<
            AudioConverterRef?,
            CPointer<UIntVar>?,
            CPointer<AudioBufferList>?,
            CPointer<CPointerVar<AudioStreamPacketDescription>>?,
            COpaquePointer?,
            Int,
        > { _, ioNum, ioData, _, userData ->
            val n = ioNum ?: return@staticCFunction -1
            val data = ioData ?: return@staticCFunction -1
            val ud = userData ?: return@staticCFunction -1
            val ctx = ud.asStableRef<CallbackContext>().get()
            ctx.fill(n, data)
        }
    }
}

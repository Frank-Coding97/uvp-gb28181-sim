package com.uvp.sim.camera

import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.G711
import com.uvp.sim.media.MediaTimebase
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatusVar
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioTime
import platform.Foundation.NSError

/**
 * iOS audio capture + encode.
 *
 * 2026-07-09 真机崩溃修:AVAudioEngine.installTapOnBus 的 format 参数必须匹配
 * bus 硬件当前的原生格式(通常 48kHz Float32,由 AVAudioSession 决定),硬编码
 * 8kHz(G.711) / 44.1kHz(AAC) 会触发 AVAEInternal SetOutputFormat NSException:
 *
 *   required condition is false: [AVAudioIONodeImpl.mm:1281:SetOutputFormat:
 *   (format.sampleRate == hwFormat.sampleRate)]
 *
 * 修法:tap format = inputFormatForBus(0u) 拿硬件原生格式,拿到 buffer 后走
 * AVAudioConverter 重采样到目标格式(8kHz Int16 mono → G.711 / 44.1kHz Int16
 * mono → AAC),再交给下游 encoder。
 *
 * 20 ms 帧率约定跟 Android 侧 streamG711 / streamAac 保持一致,以便共用 RTP
 * packer 逻辑。硬件 tap 用 100ms bufferSize hint,让 iOS 自行决定实际大小。
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosAudioStreamer(private val config: AudioCaptureConfig) {

    private var engine: AVAudioEngine? = null

    /** T-B2-4:AAC 分支持有的 encoder。stop 时 close。 */
    private var aacEncoder: com.uvp.sim.media.IosAacEncoder? = null

    /**
     * Emit compressed audio frames.
     */
    fun stream(): Flow<AudioFrame> = when (config.codec) {
        AudioCodec.G711A, AudioCodec.G711U -> streamG711()
        AudioCodec.AAC -> streamAac()
    }

    private fun streamG711(): Flow<AudioFrame> = callbackFlow {
        if (!UplinkAudioSession.acquire()) {
            close(IllegalStateException("AVAudioSession activation failed"))
            return@callbackFlow
        }
        var audioSessionHeld = true
        fun releaseAudioSession() {
            if (!audioSessionHeld) return
            audioSessionHeld = false
            UplinkAudioSession.release()
        }

        val eng = AVAudioEngine()
        val input = eng.inputNode
        val hwFormat = input.inputFormatForBus(0u)

        if (hwFormat.sampleRate <= 0.0) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_AUDIO_START_FAILED codec=${config.codec} bus0 hw sampleRate=${hwFormat.sampleRate}"
            )
            releaseAudioSession()
            close(IllegalStateException("bus0 hw sampleRate<=0"))
            return@callbackFlow
        }

        val targetFormat = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = SAMPLE_RATE_HZ,
            channels = CHANNELS,
            interleaved = true,
        )
        val converter = AVAudioConverter(fromFormat = hwFormat, toFormat = targetFormat)
        if (converter == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_AUDIO_START_FAILED codec=${config.codec} converter create failed " +
                    "hw=${hwFormat.sampleRate}Hz/${hwFormat.channelCount}ch → target=${SAMPLE_RATE_HZ.toInt()}Hz/${CHANNELS.toInt()}ch"
            )
            releaseAudioSession()
            close(IllegalStateException("AVAudioConverter create failed"))
            return@callbackFlow
        }

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_AUDIO_HW_FORMAT codec=${config.codec} " +
                "hw=${hwFormat.sampleRate}Hz/${hwFormat.channelCount}ch " +
                "→ target=${SAMPLE_RATE_HZ.toInt()}Hz/${CHANNELS.toInt()}ch"
        )

        var tapCallbacks = 0L
        var emittedFrames = 0L

        input.installTapOnBus(
            bus = 0u,
            bufferSize = HW_TAP_BUFFER_FRAMES,
            format = hwFormat,
        ) { buffer: AVAudioPCMBuffer?, _: AVAudioTime? ->
            if (buffer == null) return@installTapOnBus
            tapCallbacks += 1
            val pcm = convertToInt16Pcm(
                inputBuffer = buffer,
                converter = converter,
                targetFormat = targetFormat,
                targetSampleRate = SAMPLE_RATE_HZ,
                hwSampleRate = hwFormat.sampleRate,
            ) ?: run {
                if (tapCallbacks <= 3L) {
                    SystemLogger.emit(
                        LogLevel.Warning,
                        LogTag.Media,
                        "IOS_AUDIO_CONVERT_EMPTY codec=${config.codec} tap=$tapCallbacks " +
                            "inputFrames=${buffer.frameLength}",
                    )
                }
                return@installTapOnBus
            }
            val frame = encodePcmToG711Frame(pcm, config.codec, MediaTimebase.nowUs())
            val result = trySend(frame)
            emittedFrames += 1
            if (emittedFrames == 1L) {
                SystemLogger.emit(
                    LogLevel.Info,
                    LogTag.Media,
                    "IOS_AUDIO_FIRST_FRAME codec=${config.codec} samples=${pcm.size} " +
                        "payload=${frame.payload.size} accepted=${result.isSuccess}",
                )
            }
        }

        eng.prepare()
        val started = memScoped {
            val errPtr = alloc<ObjCObjectVar<NSError?>>()
            val ok = eng.startAndReturnError(errPtr.ptr)
            if (!ok) {
                val desc = errPtr.value?.localizedDescription ?: "unknown"
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_AUDIO_START_FAILED codec=${config.codec} error=$desc",
                )
            }
            ok
        }
        if (!started) {
            input.removeTapOnBus(0u)
            releaseAudioSession()
            close(IllegalStateException("AVAudioEngine.start failed"))
            return@callbackFlow
        }

        // T-E2-2:activeCount++ 只在真正 start 成功后,与 stop 侧的 -- 配对。
        activeCountAtomic.incrementAndGet()

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_AUDIO_START codec=${config.codec} sr=${SAMPLE_RATE_HZ.toInt()} ch=${CHANNELS.toInt()}",
        )
        engine = eng

        awaitClose {
            input.removeTapOnBus(0u)
            eng.stop()
            engine = null
            releaseAudioSession()
            // T-E2-2:配对 --,clamp 不减到负(多次 close 幂等)。
            decrementActiveCountClamped()
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_AUDIO_STOP codec=${config.codec}")
        }
    }

    /**
     * T-B2-4:AAC 分支。tap 用硬件原生格式 → AVAudioConverter 重采样到 44.1 kHz
     * Int16 mono → IosAacEncoder。encoder 内部累积 1024 samples 才 emit 一帧
     * AAC(即约每 3 chunks emit 2 frame)。
     */
    private fun streamAac(): Flow<AudioFrame> = callbackFlow {
        if (!UplinkAudioSession.acquire()) {
            close(IllegalStateException("AVAudioSession activation failed"))
            return@callbackFlow
        }
        var audioSessionHeld = true
        fun releaseAudioSession() {
            if (!audioSessionHeld) return
            audioSessionHeld = false
            UplinkAudioSession.release()
        }

        val eng = AVAudioEngine()
        val input = eng.inputNode
        val hwFormat = input.inputFormatForBus(0u)
        val targetSampleRate = targetAudioSampleRate(config)

        if (hwFormat.sampleRate <= 0.0) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_AUDIO_START_FAILED codec=AAC bus0 hw sampleRate=${hwFormat.sampleRate}"
            )
            releaseAudioSession()
            close(IllegalStateException("bus0 hw sampleRate<=0"))
            return@callbackFlow
        }

        val targetFormat = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = targetSampleRate,
            channels = CHANNELS,
            interleaved = true,
        )
        val converter = AVAudioConverter(fromFormat = hwFormat, toFormat = targetFormat)
        if (converter == null) {
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_AUDIO_START_FAILED codec=AAC converter create failed " +
                    "hw=${hwFormat.sampleRate}Hz/${hwFormat.channelCount}ch → target=${targetSampleRate.toInt()}Hz/${CHANNELS.toInt()}ch"
            )
            releaseAudioSession()
            close(IllegalStateException("AVAudioConverter create failed for AAC"))
            return@callbackFlow
        }

        val encoder = com.uvp.sim.media.IosAacEncoder(
            pcmSampleRateHz = targetSampleRate,
            channelCount = CHANNELS,
            aacSampleRateHz = targetSampleRate,
        )
        aacEncoder = encoder

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_AUDIO_HW_FORMAT codec=AAC " +
                "hw=${hwFormat.sampleRate}Hz/${hwFormat.channelCount}ch " +
                "→ target=${targetSampleRate.toInt()}Hz/${CHANNELS.toInt()}ch"
        )

        var tapCallbacks = 0L
        var emittedFrames = 0L

        input.installTapOnBus(
            bus = 0u,
            bufferSize = HW_TAP_BUFFER_FRAMES,
            format = hwFormat,
        ) { buffer: AVAudioPCMBuffer?, _: AVAudioTime? ->
            if (buffer == null) return@installTapOnBus
            tapCallbacks += 1
            val pcm = convertToInt16Pcm(
                inputBuffer = buffer,
                converter = converter,
                targetFormat = targetFormat,
                targetSampleRate = targetSampleRate,
                hwSampleRate = hwFormat.sampleRate,
            ) ?: run {
                if (tapCallbacks <= 3L) {
                    SystemLogger.emit(
                        LogLevel.Warning,
                        LogTag.Media,
                        "IOS_AUDIO_CONVERT_EMPTY codec=AAC tap=$tapCallbacks inputFrames=${buffer.frameLength}",
                    )
                }
                return@installTapOnBus
            }
            val aacFrames = encoder.encode(pcm, MediaTimebase.nowUs())
            for (f in aacFrames) {
                val result = trySend(f)
                emittedFrames += 1
                if (emittedFrames == 1L) {
                    SystemLogger.emit(
                        LogLevel.Info,
                        LogTag.Media,
                        "IOS_AUDIO_FIRST_FRAME codec=AAC samples=${pcm.size} " +
                            "payload=${f.payload.size} accepted=${result.isSuccess}",
                    )
                }
            }
        }

        eng.prepare()
        val started = memScoped {
            val errPtr = alloc<ObjCObjectVar<NSError?>>()
            val ok = eng.startAndReturnError(errPtr.ptr)
            if (!ok) {
                val desc = errPtr.value?.localizedDescription ?: "unknown"
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_AUDIO_START_FAILED codec=${config.codec} error=$desc",
                )
            }
            ok
        }
        if (!started) {
            input.removeTapOnBus(0u)
            encoder.close()
            aacEncoder = null
            releaseAudioSession()
            close(IllegalStateException("AVAudioEngine.start failed for AAC"))
            return@callbackFlow
        }

        activeCountAtomic.incrementAndGet()

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_AUDIO_START codec=AAC sr=${targetSampleRate.toInt()} ch=${CHANNELS.toInt()}",
        )
        engine = eng

        awaitClose {
            input.removeTapOnBus(0u)
            eng.stop()
            engine = null
            encoder.close()
            aacEncoder = null
            releaseAudioSession()
            decrementActiveCountClamped()
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_AUDIO_STOP codec=AAC")
        }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun stop() {
        engine?.let {
            it.inputNode.removeTapOnBus(0u)
            it.stop()
        }
        engine = null
        aacEncoder?.close()
        aacEncoder = null
        // T-E2-2:activeCount 不在 stop() 里减,统一走 callbackFlow 的 awaitClose 路径。
        // awaitClose 会在 Flow 被 cancel/close 时保证触发一次,与 increment 严格配对。
    }

    /** Test hook — verify the streamer accepts the configured codec without crashing. */
    fun configuredCodec(): AudioCodec = config.codec

    companion object {
        val activeCount: Int
            get() = activeCountAtomic.value

        internal val activeCountAtomic: AtomicInt = AtomicInt(0)

        internal fun decrementActiveCountClamped() {
            while (true) {
                val v = activeCountAtomic.value
                if (v <= 0) return
                if (activeCountAtomic.compareAndSet(v, v - 1)) return
            }
        }

        internal fun resetActiveCountForTest() {
            activeCountAtomic.value = 0
        }

        const val SAMPLE_RATE_HZ: Double = 8000.0
        const val CHANNELS: UInt = 1u

        // AAC 分支采样率 44.1 kHz(plan §3.2.2 Q5 决策)。
        const val AAC_SAMPLE_RATE_HZ: Double = 44_100.0

        // 硬件 tap bufferSize:100ms @ 48kHz ≈ 4800 samples,系统会按需自行决定实际值。
        const val HW_TAP_BUFFER_FRAMES: UInt = 4800u
    }
}

/**
 * 用 AVAudioConverter 把硬件 buffer 重采样到 Int16 mono 目标格式,返回 Kotlin ShortArray。
 *
 * pull-based:每次调用只喂一次输入 buffer(通过 [inputBlock]),本轮没有更多数据时
 * 返回 NoDataNow。converter 会跨 tap 回调持续复用,不能返回 EndOfStream,否则首轮
 * 转换后会把整条实时音频流标记为结束。
 * 内部按采样率比 + 冗余系数预估 output frameCapacity,避免 output 溢出。
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun convertToInt16Pcm(
    inputBuffer: AVAudioPCMBuffer,
    converter: AVAudioConverter,
    targetFormat: AVAudioFormat,
    targetSampleRate: Double,
    hwSampleRate: Double,
): ShortArray? {
    val inFrames = inputBuffer.frameLength.toInt()
    if (inFrames <= 0) return null

    // 采样率比推导 output frames,加 32 冗余避免边界丢帧。
    val ratio = targetSampleRate / hwSampleRate
    val outCapacity = ((inFrames * ratio).toInt() + 32).coerceAtLeast(64).toUInt()
    val outBuffer = AVAudioPCMBuffer(
        pCMFormat = targetFormat,
        frameCapacity = outCapacity,
    ) ?: return null

    var provided = false
    val inputBlock: (uint: UInt, outStatus: CPointer<AVAudioConverterInputStatusVar>?) -> AVAudioPCMBuffer? = block@{ _, outStatus ->
        if (provided) {
            outStatus?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
            return@block null
        }
        provided = true
        outStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
        inputBuffer
    }

    memScoped {
        val errPtr = alloc<ObjCObjectVar<NSError?>>()
        converter.convertToBuffer(
            outBuffer,
            error = errPtr.ptr,
            withInputFromBlock = inputBlock,
        )
        errPtr.value?.let { err ->
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "IOS_AUDIO_CONVERT_ERROR ${err.localizedDescription}"
            )
        }
    }

    val outFrames = outBuffer.frameLength.toInt()
    if (outFrames <= 0) return null

    val outPtr: CPointer<ShortVar> = outBuffer.int16ChannelData?.pointed?.value ?: return null
    val pcm = ShortArray(outFrames)
    for (i in 0 until outFrames) {
        pcm[i] = outPtr[i]
    }
    return pcm
}

/**
 * Encode one PCM buffer to a G.711 [AudioFrame]. Public utility so the
 * AVAudioEngine tap callback (and iosTest fixtures) can share the codec
 * branching without duplicating the G711 select.
 */
internal fun encodePcmToG711Frame(
    pcm: ShortArray,
    codec: AudioCodec,
    timestampUs: Long,
): AudioFrame {
    val payload = when (codec) {
        AudioCodec.G711A -> G711.encodeAlaw(pcm)
        AudioCodec.G711U -> G711.encodeUlaw(pcm)
        AudioCodec.AAC -> error("encodePcmToG711Frame called with AAC codec")
    }
    return AudioFrame(payload = payload, timestampUs = timestampUs, codec = codec)
}

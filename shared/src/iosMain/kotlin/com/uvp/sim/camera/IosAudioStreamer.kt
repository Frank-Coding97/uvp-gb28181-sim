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
import platform.AVFAudio.AVAudioCommonFormat
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioTime
import platform.Foundation.NSError

/**
 * iOS audio capture + encode.
 *
 * T8-follow-up: AVAudioEngine + installTapOnBus wiring. The tap block is an ObjC
 * block (not a C function pointer), which Kotlin/Native's cinterop wraps
 * automatically from a Kotlin lambda when the signature matches. Contrast with
 * [IosCameraStreamer], which needs `staticCFunction` because VTCompression's
 * output callback is a C function pointer.
 *
 * Design:
 *
 *   1. AVAudioEngine.inputNode → 16-bit interleaved PCM at 8 kHz mono
 *   2. installTapOnBus(0, bufferSize = 160 samples / 20ms)
 *   3. For each buffer: samples → G711.encodeAlaw/encodeUlaw → AudioFrame emit
 *   4. Timestamp: MediaTimebase.nowUs() at emit time
 *
 * The 20 ms frame convention matches Android AndroidAudioStreamer.streamG711
 * for cross-platform RTP packer parity. Note the hardware may deliver a
 * buffer with frameLength != 160 (early call, sample rate mismatch); we
 * defensively read frameLength and encode exactly that many samples.
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
        val eng = AVAudioEngine()
        val input = eng.inputNode
        val format = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = SAMPLE_RATE_HZ,
            channels = CHANNELS,
            interleaved = true,
        )

        input.installTapOnBus(
            bus = 0u,
            bufferSize = BUFFER_FRAMES,
            format = format,
        ) { buffer: AVAudioPCMBuffer?, _: AVAudioTime? ->
            if (buffer == null) return@installTapOnBus
            val frames = buffer.frameLength.toInt()
            if (frames <= 0) return@installTapOnBus
            val channelPtr: CPointer<ShortVar> =
                buffer.int16ChannelData?.pointed?.value ?: return@installTapOnBus

            val pcm = ShortArray(frames)
            for (i in 0 until frames) {
                pcm[i] = channelPtr[i]
            }
            val frame = encodePcmToG711Frame(pcm, config.codec, MediaTimebase.nowUs())
            trySend(frame)
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
            // T-E2-2:配对 --,clamp 不减到负(多次 close 幂等)。
            decrementActiveCountClamped()
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_AUDIO_STOP codec=${config.codec}")
        }
    }

    /**
     * T-B2-4:AAC 分支。AVAudioEngine 44.1 kHz mono PCM tap → IosAacEncoder → AAC + ADTS。
     * 20 ms 帧率(882 samples per tap chunk),encoder 内部累积到 1024 samples 才 emit
     * 一帧 AAC(即约每 3 chunks emit 2 frame)。
     */
    private fun streamAac(): Flow<AudioFrame> = callbackFlow {
        val eng = AVAudioEngine()
        val input = eng.inputNode
        val format = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = AAC_SAMPLE_RATE_HZ,
            channels = CHANNELS,
            interleaved = true,
        )
        val encoder = com.uvp.sim.media.IosAacEncoder(
            pcmSampleRateHz = AAC_SAMPLE_RATE_HZ,
            channelCount = CHANNELS,
            aacSampleRateHz = AAC_SAMPLE_RATE_HZ,
        )
        aacEncoder = encoder

        input.installTapOnBus(
            bus = 0u,
            bufferSize = AAC_BUFFER_FRAMES,
            format = format,
        ) { buffer: AVAudioPCMBuffer?, _: AVAudioTime? ->
            if (buffer == null) return@installTapOnBus
            val frames = buffer.frameLength.toInt()
            if (frames <= 0) return@installTapOnBus
            val channelPtr: CPointer<ShortVar> =
                buffer.int16ChannelData?.pointed?.value ?: return@installTapOnBus

            val pcm = ShortArray(frames)
            for (i in 0 until frames) {
                pcm[i] = channelPtr[i]
            }
            val aacFrames = encoder.encode(pcm, MediaTimebase.nowUs())
            for (f in aacFrames) {
                trySend(f)
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
            close(IllegalStateException("AVAudioEngine.start failed for AAC"))
            return@callbackFlow
        }

        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_AUDIO_START codec=AAC sr=${AAC_SAMPLE_RATE_HZ.toInt()} ch=${CHANNELS.toInt()}",
        )
        engine = eng

        awaitClose {
            input.removeTapOnBus(0u)
            eng.stop()
            engine = null
            encoder.close()
            aacEncoder = null
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
        /**
         * T-E2-2 · 全局录像 audio tap 活跃计数。
         *
         * [BroadcastBusyGate] iOS actual 读这个值:> 0 表示"有 tap 在采集 mic",
         * broadcast 走 ERROR busy 分支(plan §5 Q4 排队策略)。
         *
         * 嵌套 start/stop 3 次 activeCount = 3;3 次 stop 后归 0;多余 stop 不减到负(clamp)。
         */
        val activeCount: Int
            get() = activeCountAtomic.value

        internal val activeCountAtomic: AtomicInt = AtomicInt(0)

        internal fun decrementActiveCountClamped() {
            // Clamp-safe decrement:if 0,do nothing;else --。多次 close/stop 幂等。
            while (true) {
                val v = activeCountAtomic.value
                if (v <= 0) return
                if (activeCountAtomic.compareAndSet(v, v - 1)) return
            }
        }

        /** 测试 hook — 重置 activeCount(fixtures 兜底)。 */
        internal fun resetActiveCountForTest() {
            activeCountAtomic.value = 0
        }

        const val SAMPLE_RATE_HZ: Double = 8000.0
        const val CHANNELS: UInt = 1u
        // 20 ms @ 8 kHz = 160 samples; matches Android streamG711 and the RTP packer.
        const val BUFFER_FRAMES: UInt = 160u

        // T-B2-4:AAC 分支采样率 44.1 kHz(plan §3.2.2 Q5 决策);tap chunk 大小
        // 882 samples ≈ 20 ms @ 44.1kHz(encoder 内部再累积到 1024 samples 触发 emit)
        const val AAC_SAMPLE_RATE_HZ: Double = 44_100.0
        const val AAC_BUFFER_FRAMES: UInt = 882u
    }
}

/**
 * Encode one 20ms PCM buffer to a G.711 [AudioFrame]. Public utility so the
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

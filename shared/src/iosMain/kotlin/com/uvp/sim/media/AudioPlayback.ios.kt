package com.uvp.sim.media

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatusVar
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioPlayerNode
import platform.Foundation.NSError
import platform.Foundation.NSLock

/** iOS 扬声器输出。输入 PCM16 先转换到音频图输出格式，再交给 PlayerNode。 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class AudioPlayback actual constructor(
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    private var engine: AVAudioEngine? = null
    private var playerNode: AVAudioPlayerNode? = null
    private var inputFormat: AVAudioFormat? = null
    private var outputFormat: AVAudioFormat? = null
    private var converter: AVAudioConverter? = null
    private val lock = NSLock()

    actual fun start(): Boolean {
        lock.lock()
        try {
            if (engine != null) return true
            if (!BroadcastAudioSession.activate(sampleRate, channelCount)) {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_PLAYBACK_SESSION_UNAVAILABLE sr=$sampleRate ch=$channelCount",
                )
                return false
            }
            return runCatching {
                val eng = AVAudioEngine()
                val node = AVAudioPlayerNode()
                val sourceFmt = AVAudioFormat(
                    commonFormat = AVAudioPCMFormatInt16,
                    sampleRate = sampleRate.toDouble(),
                    channels = channelCount.toUInt(),
                    interleaved = true,
                ) ?: error("source format unavailable")
                val mixerFmt = eng.mainMixerNode.outputFormatForBus(0u)
                require(mixerFmt.sampleRate > 0.0 && mixerFmt.channelCount > 0u) {
                    "mixer format unavailable sr=${mixerFmt.sampleRate} ch=${mixerFmt.channelCount}"
                }
                val cvt = AVAudioConverter(fromFormat = sourceFmt, toFormat = mixerFmt)
                    ?: error("converter unavailable")

                eng.attachNode(node)
                // 真机不接受把 8 kHz 输入格式直接设为 PlayerNode 输出格式。
                eng.connect(node, to = eng.mainMixerNode, format = mixerFmt)
                eng.prepare()
                val started = memScoped {
                    val errPtr = alloc<ObjCObjectVar<NSError?>>()
                    val ok = eng.startAndReturnError(errPtr.ptr)
                    if (!ok) {
                        SystemLogger.emit(
                            LogLevel.Error,
                            LogTag.Media,
                            "IOS_PLAYBACK_START_FAILED sr=$sampleRate ch=$channelCount " +
                                "error=${errPtr.value?.localizedDescription ?: "unknown"}",
                        )
                    }
                    ok
                }
                if (!started) {
                    eng.detachNode(node)
                    error("AVAudioEngine.start failed")
                }

                node.play()
                engine = eng
                playerNode = node
                inputFormat = sourceFmt
                outputFormat = mixerFmt
                converter = cvt
                SystemLogger.emit(
                    LogLevel.Info,
                    LogTag.Media,
                    "IOS_PLAYBACK_START sr=$sampleRate ch=$channelCount " +
                        "out=${mixerFmt.sampleRate}Hz/${mixerFmt.channelCount}ch",
                )
            }.onFailure { e ->
                SystemLogger.emit(
                    LogLevel.Error,
                    LogTag.Media,
                    "IOS_PLAYBACK_START_EXCEPTION ${e::class.simpleName}: ${e.message}",
                )
                engine = null
                playerNode = null
                inputFormat = null
                outputFormat = null
                converter = null
                BroadcastAudioSession.deactivate()
            }.isSuccess
        } finally {
            lock.unlock()
        }
    }

    actual fun write(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        lock.lock()
        try {
            val node = playerNode ?: return
            val sourceFmt = inputFormat ?: return
            val targetFmt = outputFormat ?: return
            val cvt = converter ?: return
            runCatching {
                val input = AVAudioPCMBuffer(
                    pCMFormat = sourceFmt,
                    frameCapacity = pcm.size.toUInt(),
                ) ?: return@runCatching
                input.frameLength = pcm.size.toUInt()
                val inputPtr: CPointer<ShortVar> =
                    input.int16ChannelData?.pointed?.value ?: return@runCatching
                for (i in pcm.indices) inputPtr[i] = pcm[i]

                val ratio = targetFmt.sampleRate / sourceFmt.sampleRate
                val capacity = (pcm.size * ratio).toInt().coerceAtLeast(1).toUInt() + 32u
                val output = AVAudioPCMBuffer(
                    pCMFormat = targetFmt,
                    frameCapacity = capacity,
                ) ?: return@runCatching
                var provided = false
                val inputBlock: (UInt, CPointer<AVAudioConverterInputStatusVar>?) -> AVAudioPCMBuffer? =
                    block@{ _, status ->
                        if (provided) {
                            status?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                            return@block null
                        }
                        provided = true
                        status?.pointed?.value = AVAudioConverterInputStatus_HaveData
                        input
                    }
                memScoped {
                    val errPtr = alloc<ObjCObjectVar<NSError?>>()
                    cvt.convertToBuffer(output, error = errPtr.ptr, withInputFromBlock = inputBlock)
                    errPtr.value?.let { error ->
                        SystemLogger.emit(
                            LogLevel.Warning,
                            LogTag.Media,
                            "IOS_PLAYBACK_CONVERT_FAILED ${error.localizedDescription}",
                        )
                    }
                }
                if (output.frameLength > 0u) node.scheduleBuffer(output, completionHandler = null)
            }
        } finally {
            lock.unlock()
        }
    }

    actual fun stop() {
        lock.lock()
        try {
            runCatching { playerNode?.stop() }
            runCatching {
                val eng = engine
                val node = playerNode
                eng?.stop()
                if (eng != null && node != null) eng.detachNode(node)
            }
            engine = null
            playerNode = null
            inputFormat = null
            outputFormat = null
            converter = null
            BroadcastAudioSession.deactivate()
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_PLAYBACK_STOP sr=$sampleRate ch=$channelCount")
        } finally {
            lock.unlock()
        }
    }
}

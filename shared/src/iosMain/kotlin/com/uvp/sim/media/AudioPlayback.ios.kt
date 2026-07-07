package com.uvp.sim.media

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
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
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioPlayerNode
import platform.Foundation.NSError

/**
 * iOS 扬声器输出 — AVAudioEngine + AVAudioPlayerNode 播 PCM Int16 单声道。
 *
 * 语音广播下行(§9.8)扬声器 iOS 实现,对齐 Android AudioTrack 语义。
 *
 * 数据流:
 *   1. [start]:attach playerNode → connect to mainMixerNode → engine.start → playerNode.play
 *   2. [write]:PCM ShortArray → AVAudioPCMBuffer → scheduleBuffer(nil options + no callback)
 *   3. [stop]:playerNode.stop → engine.stop → detach
 *
 * 失败回退:AVAudioEngine 硬件初始化失败(session 冲突 / 无扬声器权限)一律吞掉
 * 打 SystemLogger,不抛给上层(对齐 Android runCatching 兜底 + plan §6 Q4)。
 *
 * 注意:AVAudioSession 路由(扬声器 vs 听筒)不在此层管,由 IosAppHost 在启动阶段
 * 配 `.playback` category(参考 PlatformRuntimeIos)。缺 session 配置时默认走系统当前 route。
 */
@OptIn(ExperimentalForeignApi::class)
actual class AudioPlayback actual constructor(
    private val sampleRate: Int,
    private val channelCount: Int
) {
    private var engine: AVAudioEngine? = null
    private var playerNode: AVAudioPlayerNode? = null
    private var format: AVAudioFormat? = null

    actual fun start() {
        // T-E1-2:先激活 AVAudioSession `.playback`(defaultToSpeaker) 让 AVAudioEngine
        // 有输出 route。失败降级到系统默认 session(可能仍有声,可能没),不阻断后续 engine.start。
        BroadcastAudioSession.activate(sampleRate, channelCount)

        // T-E1-3 采样率转换决策(2026-07-07):
        //
        // 8kHz PCM Int16 直接 attach 到 mainMixerNode 走 AVAudioEngine 内建 auto-resampling。
        // - iOS 13+ AVAudioEngine 会用 mainMixerNode.outputFormat.sampleRate(通常 44.1kHz)
        //   自动上采样,不需要业务层挂 AVAudioConverter。
        // - spike(T-E1-0)证实 8kHz PCM 走这条路径不 throw + PlayerNode.play 返回。
        // - Android 上 AudioTrack(8kHz)是硬件直连,不需要 resample。iOS 交给 mixer 效果类似。
        //
        // 若真机联调(T-E3-4)发现破音 / 变速,回填 AVAudioConverter(8kHz → 44.1kHz)方案。
        runCatching {
            val eng = AVAudioEngine()
            val node = AVAudioPlayerNode()
            val fmt = AVAudioFormat(
                commonFormat = AVAudioPCMFormatInt16,
                sampleRate = sampleRate.toDouble(),
                channels = channelCount.toUInt(),
                interleaved = true,
            ) ?: run {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "IOS_PLAYBACK_FORMAT_NULL sr=$sampleRate ch=$channelCount",
                )
                return@runCatching
            }

            eng.attachNode(node)
            eng.connect(node, to = eng.mainMixerNode, format = fmt)

            eng.prepare()
            val ok = memScoped {
                val errPtr = alloc<ObjCObjectVar<NSError?>>()
                val started = eng.startAndReturnError(errPtr.ptr)
                if (!started) {
                    val desc = errPtr.value?.localizedDescription ?: "unknown"
                    SystemLogger.emit(
                        LogLevel.Error, LogTag.Media,
                        "IOS_PLAYBACK_START_FAILED sr=$sampleRate ch=$channelCount error=$desc",
                    )
                }
                started
            }
            if (!ok) {
                eng.detachNode(node)
                return@runCatching
            }

            node.play()
            engine = eng
            playerNode = node
            format = fmt
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "IOS_PLAYBACK_START sr=$sampleRate ch=$channelCount",
            )
        }.onFailure { e ->
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_PLAYBACK_START_EXCEPTION ${e::class.simpleName}: ${e.message}",
            )
            engine = null
            playerNode = null
            format = null
        }
    }

    actual fun write(pcm: ShortArray) {
        val node = playerNode ?: return
        val fmt = format ?: return
        if (pcm.isEmpty()) return
        runCatching {
            val frames = pcm.size.toUInt()
            val buffer = AVAudioPCMBuffer(pCMFormat = fmt, frameCapacity = frames) ?: return@runCatching
            buffer.frameLength = frames
            val channelPtr: CPointer<ShortVar> =
                buffer.int16ChannelData?.pointed?.value ?: return@runCatching
            for (i in 0 until pcm.size) {
                channelPtr[i] = pcm[i]
            }
            // completionHandler = null:不关心播完回调,fire-and-forget
            node.scheduleBuffer(buffer, completionHandler = null)
        }
    }

    actual fun stop() {
        runCatching { playerNode?.stop() }
        runCatching {
            val eng = engine
            val node = playerNode
            eng?.stop()
            if (eng != null && node != null) {
                eng.detachNode(node)
            }
        }
        engine = null
        playerNode = null
        format = null
        // T-E1-2:deactivate 与 activate 配对,让其他 audio app 能 resume。
        // 幂等,活跃计数在 BroadcastAudioSession 内部管。
        BroadcastAudioSession.deactivate()
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "IOS_PLAYBACK_STOP sr=$sampleRate ch=$channelCount",
        )
    }
}

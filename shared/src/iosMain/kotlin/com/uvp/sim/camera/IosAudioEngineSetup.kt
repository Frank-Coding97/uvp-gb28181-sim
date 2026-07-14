package com.uvp.sim.camera

import com.uvp.sim.media.AudioCodec
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioInputNode
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.Foundation.NSError

/**
 * cross-review v1.1 refactor A(from [IosAudioStreamer]):
 * 抽出 streamG711 / streamAac 前置的公共 setup —— AVAudioEngine 创建 / 硬件 bus0
 * 采样率校验 / AVAudioConverter 建立 / IOS_AUDIO_HW_FORMAT 日志。
 *
 * 拆前两个 flow 各自维护:
 *   AVAudioEngine → inputNode → hwFormat → sampleRate 校验 → targetFormat 组装 →
 *   AVAudioConverter → 失败回退(releaseAudioSession) → HW_FORMAT 日志
 *
 * 拆完 flow 只关心:tap 里的编码 lambda + engine.prepare/start + acquireActiveCount +
 * awaitClose 清理链;通用 setup 集中在这里,新增 codec 或采样率变化只改一处。
 *
 * **不改语义**:所有日志格式 / 错误分支 / 返回值 nullable 处理与拆前 1:1 对齐。
 */
@OptIn(ExperimentalForeignApi::class)
internal data class AudioEngineSetup(
    val engine: AVAudioEngine,
    val input: AVAudioInputNode,
    val hwFormat: AVAudioFormat,
    val targetFormat: AVAudioFormat,
    val converter: AVAudioConverter,
)

/**
 * 建 AVAudioEngine + inputNode + hwFormat 校验 + AVAudioConverter setup。
 * 失败时:
 *   - 已通过 SystemLogger emit 相同格式的 Error 日志(codec + 原因),跟拆前完全一致
 *   - 调用 [onSetupError] 让上层做资源回滚(通常 releaseAudioSession)
 *   - 返回 null
 *
 * 成功不 acquireActiveCount / engine.prepare —— 这两个留给调用方在 tap 装完后调,
 * 失败时可干净回退。
 */
@OptIn(ExperimentalForeignApi::class)
internal fun buildAudioEngineSetup(
    codec: AudioCodec,
    targetSampleRate: Double,
    channels: UInt,
    onSetupError: () -> Unit,
): AudioEngineSetup? {
    val eng = AVAudioEngine()
    val input = eng.inputNode
    val hwFormat = input.inputFormatForBus(0u)

    if (hwFormat.sampleRate <= 0.0) {
        SystemLogger.emit(
            LogLevel.Error, LogTag.Media,
            "IOS_AUDIO_START_FAILED codec=$codec bus0 hw sampleRate=${hwFormat.sampleRate}",
        )
        onSetupError()
        return null
    }

    val targetFormat = AVAudioFormat(
        commonFormat = AVAudioPCMFormatInt16,
        sampleRate = targetSampleRate,
        channels = channels,
        interleaved = true,
    )
    val converter = AVAudioConverter(fromFormat = hwFormat, toFormat = targetFormat)
    if (converter == null) {
        SystemLogger.emit(
            LogLevel.Error, LogTag.Media,
            "IOS_AUDIO_START_FAILED codec=$codec converter create failed " +
                "hw=${hwFormat.sampleRate}Hz/${hwFormat.channelCount}ch → " +
                "target=${targetSampleRate.toInt()}Hz/${channels.toInt()}ch",
        )
        onSetupError()
        return null
    }

    SystemLogger.emit(
        LogLevel.Info, LogTag.Media,
        "IOS_AUDIO_HW_FORMAT codec=$codec " +
            "hw=${hwFormat.sampleRate}Hz/${hwFormat.channelCount}ch " +
            "→ target=${targetSampleRate.toInt()}Hz/${channels.toInt()}ch",
    )

    return AudioEngineSetup(eng, input, hwFormat, targetFormat, converter)
}

/**
 * eng.prepare + startAndReturnError,失败已 log(codec + error desc)。
 * 返回是否 start 成功。调用方失败时负责 removeTapOnBus + releaseAudioSession + close(...)。
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal fun startAudioEngine(eng: AVAudioEngine, codec: AudioCodec): Boolean {
    eng.prepare()
    return memScoped {
        val errPtr = alloc<ObjCObjectVar<NSError?>>()
        val ok = eng.startAndReturnError(errPtr.ptr)
        if (!ok) {
            val desc = errPtr.value?.localizedDescription ?: "unknown"
            SystemLogger.emit(
                LogLevel.Error, LogTag.Media,
                "IOS_AUDIO_START_FAILED codec=$codec error=$desc",
            )
        }
        ok
    }
}

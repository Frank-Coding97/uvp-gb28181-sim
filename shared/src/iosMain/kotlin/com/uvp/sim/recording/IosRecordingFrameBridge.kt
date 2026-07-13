package com.uvp.sim.recording

import com.uvp.sim.api.LogTag
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.H264Frame
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger

internal interface IosVideoFrameSink {
    fun feedVideoFrame(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean)
}

/**
 * T-B3-0:audio 侧 sink 契约。与 [IosVideoFrameSink] 独立,便于 IosRecordingService
 * 单实现同时挂 video + audio,也便于 test 只挂一侧。
 */
internal interface IosAudioFrameSink {
    fun feedAudioFrame(payload: ByteArray, ptsUs: Long, codec: AudioCodec)
}

internal object IosRecordingFrameBridge {
    @kotlin.concurrent.Volatile
    private var videoSink: IosVideoFrameSink? = null

    @kotlin.concurrent.Volatile
    private var audioSink: IosAudioFrameSink? = null

    /**
     * T-B3-0:二参数化 publish。允许一侧为 null(如仅推流不录像的通道)。
     * 单参 overload 向后兼容 v1.2 调用点(video-only)。
     */
    fun publish(video: IosVideoFrameSink?, audio: IosAudioFrameSink?) {
        videoSink = video
        audioSink = audio
    }

    /** v1.2 兼容:老装配点只挂 video,audio 保留原 sink 不变。 */
    fun publish(video: IosVideoFrameSink?) {
        videoSink = video
    }

    fun onVideoFrame(frame: H264Frame) {
        val target = videoSink ?: return
        runCatching {
            target.feedVideoFrame(
                nalUnits = frame.nalUnits,
                ptsUs = frame.timestampUs,
                isKeyFrame = frame.isKeyFrame,
            )
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Media,
                "IOS_RECORDING_FRAME_BRIDGE_DROP msg=${it.message}",
            )
        }
    }

    /**
     * T-B3-0:audio 帧分发。null sink 静默(不 crash),异常包 runCatching。
     */
    fun onAudioFrame(frame: AudioFrame) {
        val target = audioSink ?: return
        runCatching {
            target.feedAudioFrame(
                payload = frame.payload,
                ptsUs = frame.timestampUs,
                codec = frame.codec,
            )
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Media,
                "IOS_RECORDING_AUDIO_BRIDGE_DROP msg=${it.message}",
            )
        }
    }
}

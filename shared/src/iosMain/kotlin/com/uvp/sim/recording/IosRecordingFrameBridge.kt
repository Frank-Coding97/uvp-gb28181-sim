package com.uvp.sim.recording

import com.uvp.sim.api.LogTag
import com.uvp.sim.media.H264Frame
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger

internal interface IosVideoFrameSink {
    fun feedVideoFrame(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean)
}

internal object IosRecordingFrameBridge {
    @kotlin.concurrent.Volatile
    private var sink: IosVideoFrameSink? = null

    fun publish(service: IosVideoFrameSink?) {
        sink = service
    }

    fun onVideoFrame(frame: H264Frame) {
        val target = sink ?: return
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
}

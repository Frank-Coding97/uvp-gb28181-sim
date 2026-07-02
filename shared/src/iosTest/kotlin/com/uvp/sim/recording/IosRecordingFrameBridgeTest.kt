package com.uvp.sim.recording

import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.NalType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IosRecordingFrameBridgeTest {

    @AfterTest
    fun clearBridge() {
        IosRecordingFrameBridge.publish(null)
    }

    @Test
    fun frame_is_ignored_when_no_sink_is_published() {
        IosRecordingFrameBridge.publish(null)

        IosRecordingFrameBridge.onVideoFrame(frame(ptsUs = 123, isKeyFrame = true))
    }

    @Test
    fun frame_is_forwarded_to_published_sink() {
        val sink = RecordingSink()
        IosRecordingFrameBridge.publish(sink)

        IosRecordingFrameBridge.onVideoFrame(frame(ptsUs = 123, isKeyFrame = true))

        assertEquals(1, sink.frames.size)
        assertEquals(123, sink.frames.single().ptsUs)
        assertEquals(true, sink.frames.single().isKeyFrame)
    }

    @Test
    fun sink_failure_does_not_escape_camera_callback() {
        IosRecordingFrameBridge.publish(FailingSink())

        IosRecordingFrameBridge.onVideoFrame(frame(ptsUs = 456, isKeyFrame = false))
    }

    private fun frame(ptsUs: Long, isKeyFrame: Boolean): H264Frame =
        H264Frame(
            nalUnits = listOf(nal(if (isKeyFrame) NalType.IDR else NalType.NON_IDR)),
            timestampUs = ptsUs,
            isKeyFrame = isKeyFrame,
        )

    private fun nal(type: Int): ByteArray = byteArrayOf((0x60 or type).toByte(), 1, 2, 3)

    private class RecordingSink : IosVideoFrameSink {
        val frames = mutableListOf<Frame>()

        override fun feedVideoFrame(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) {
            frames += Frame(nalUnits, ptsUs, isKeyFrame)
        }
    }

    private class FailingSink : IosVideoFrameSink {
        override fun feedVideoFrame(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) {
            error("boom")
        }
    }

    private data class Frame(
        val nalUnits: List<ByteArray>,
        val ptsUs: Long,
        val isKeyFrame: Boolean,
    )
}

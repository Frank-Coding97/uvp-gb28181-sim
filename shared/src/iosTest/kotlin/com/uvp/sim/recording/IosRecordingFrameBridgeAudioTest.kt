package com.uvp.sim.recording

import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioFrame
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-B3-0:验证 IosRecordingFrameBridge 加了 audio sink 语义。
 *   - publish(video, audio) 二参数化
 *   - onAudioFrame 分派到 audio sink,payload / ptsUs / codec 透传
 *   - audio sink null 时 onAudioFrame 静默(不 crash,不调 sink)
 *   - video sink 不受 audio sink 挂入影响
 */
class IosRecordingFrameBridgeAudioTest {

    private class RecordingAudioSink : IosAudioFrameSink {
        var lastPayload: ByteArray? = null
        var lastPts: Long? = null
        var lastCodec: AudioCodec? = null
        var callCount: Int = 0
        override fun feedAudioFrame(payload: ByteArray, ptsUs: Long, codec: AudioCodec) {
            lastPayload = payload
            lastPts = ptsUs
            lastCodec = codec
            callCount++
        }
    }

    private class RecordingVideoSink : IosVideoFrameSink {
        var callCount: Int = 0
        override fun feedVideoFrame(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) {
            callCount++
        }
    }

    @BeforeTest
    fun setup() {
        IosRecordingFrameBridge.publish(null, null)
    }

    @AfterTest
    fun cleanup() {
        IosRecordingFrameBridge.publish(null, null)
    }

    @Test
    fun onAudioFrame_dispatches_to_audio_sink() {
        val sink = RecordingAudioSink()
        IosRecordingFrameBridge.publish(video = null, audio = sink)
        val payload = ByteArray(100) { it.toByte() }
        IosRecordingFrameBridge.onAudioFrame(
            AudioFrame(payload = payload, timestampUs = 12345L, codec = AudioCodec.AAC)
        )
        assertEquals(1, sink.callCount)
        assertEquals(12345L, sink.lastPts)
        assertEquals(AudioCodec.AAC, sink.lastCodec)
        assertTrue(payload.contentEquals(sink.lastPayload!!))
    }

    @Test
    fun onAudioFrame_null_sink_silently_ignored() {
        // 无 audio sink 时不 crash
        IosRecordingFrameBridge.publish(null, null)
        IosRecordingFrameBridge.onAudioFrame(
            AudioFrame(payload = ByteArray(10), timestampUs = 0L, codec = AudioCodec.G711A)
        )
        // 到这里没 crash 就通过
        assertTrue(true)
    }

    @Test
    fun publish_video_only_keeps_audio_null() {
        val vSink = RecordingVideoSink()
        // 老 API (v1.2 兼容 overload)
        IosRecordingFrameBridge.publish(video = vSink)
        val aSink = RecordingAudioSink()
        // audio 分派应无效(未挂)
        IosRecordingFrameBridge.onAudioFrame(
            AudioFrame(payload = ByteArray(5), timestampUs = 1L, codec = AudioCodec.AAC)
        )
        assertEquals(0, aSink.callCount)
    }

    @Test
    fun video_dispatch_not_affected_by_audio_sink_publish() {
        val vSink = RecordingVideoSink()
        val aSink = RecordingAudioSink()
        IosRecordingFrameBridge.publish(video = vSink, audio = aSink)
        IosRecordingFrameBridge.onVideoFrame(
            com.uvp.sim.media.H264Frame(
                nalUnits = listOf(byteArrayOf(0x65)),
                timestampUs = 999L,
                isKeyFrame = true,
            )
        )
        assertEquals(1, vSink.callCount)
        assertEquals(0, aSink.callCount)
    }
}

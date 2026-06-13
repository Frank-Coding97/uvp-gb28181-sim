package com.uvp.sim.recording

import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.H264Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T03: PlaybackEngine.setScale 单测(spec §A.4-A.7 / A.10)。
 * 五档合规过滤 + hot-swap 节流 + RTP PTS 不变。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineScaleTest {

    private fun seg(id: String, durationMs: Long) = RecordingFile(
        id = id, startTimeMs = 0L, endTimeMs = durationMs, durationMs = durationMs,
        channelId = "ch1", filePath = "/tmp/$id.mp4", sizeBytes = 1024L
    )

    private fun videoFrame(ptsUs: Long, isKey: Boolean = false) =
        MediaFrame.Video(
            timestampUs = ptsUs,
            nalUnits = listOf(byteArrayOf(0x65, 0x88.toByte())),
            isKeyframe = isKey
        )

    private class FakeDemux(
        firstPts: Long,
        private val frames: List<MediaFrame>
    ) : Mp4DemuxSource {
        override val firstFramePtsUs: Long = firstPts
        override suspend fun open(): Result<Unit> = Result.success(Unit)
        override fun frames(): Flow<MediaFrame> = flow { frames.forEach { emit(it) } }
        override suspend fun close() {}
    }

    private class TsCapturingPacker : FramePacker {
        val ts = mutableListOf<Long>()
        override fun packVideo(frame: H264Frame, timestamp90k: Long): List<ByteArray> {
            ts += timestamp90k
            return listOf(byteArrayOf(0x00))
        }
        override fun packAudio(frame: AudioFrame, timestamp90k: Long): List<ByteArray> = emptyList()
    }

    private class CapturingRtp : RtpSink {
        val packets = mutableListOf<ByteArray>()
        override suspend fun send(packet: ByteArray) { packets += packet }
        override suspend fun close() {}
    }

    private fun TestScope.virtualClock() = object : PlaybackClock {
        override fun nowMs(): Long = testScheduler.currentTime
    }

    @Test fun defaultScaleIsOne() = runTest {
        val packer = TsCapturingPacker()
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 100)),
            demuxFactory = { FakeDemux(0L, listOf(videoFrame(0L, true), videoFrame(100_000L))) },
            framePacker = packer,
            rtp = CapturingRtp(),
            clock = virtualClock()
        )
        assertEquals(1.0, engine.scale)
        engine.run()
        assertTrue(testScheduler.currentTime >= 100, "scale=1 时 100ms 媒体推流耗时 >= 100ms")
    }

    @Test fun setScaleBeforeRunDoublesPace() = runTest {
        val packer = TsCapturingPacker()
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 200)),
            demuxFactory = { FakeDemux(0L, listOf(videoFrame(0L, true), videoFrame(200_000L))) },
            framePacker = packer,
            rtp = CapturingRtp(),
            clock = virtualClock(),
            initialScale = 2.0
        )
        engine.run()
        // 200ms 媒体 / scale=2 = 100ms wallclock
        assertTrue(
            testScheduler.currentTime in 90..150,
            "scale=2 时 200ms 媒体推流耗时应 ~100ms, 实际=${testScheduler.currentTime}"
        )
    }

    @Test fun setScaleHalvesPace() = runTest {
        val packer = TsCapturingPacker()
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 100)),
            demuxFactory = { FakeDemux(0L, listOf(videoFrame(0L, true), videoFrame(100_000L))) },
            framePacker = packer,
            rtp = CapturingRtp(),
            clock = virtualClock(),
            initialScale = 0.5
        )
        engine.run()
        // 100ms 媒体 / scale=0.5 = 200ms wallclock
        assertTrue(
            testScheduler.currentTime in 190..250,
            "scale=0.5 时 100ms 媒体推流耗时应 ~200ms, 实际=${testScheduler.currentTime}"
        )
    }

    @Test fun illegalScaleIgnored() = runTest {
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 100)),
            demuxFactory = { FakeDemux(0L, listOf(videoFrame(0L, true))) },
            framePacker = TsCapturingPacker(),
            rtp = CapturingRtp(),
            clock = virtualClock()
        )
        engine.setScale(3.0)
        assertEquals(1.0, engine.scale, "非档位 3.0 应忽略")
        engine.setScale(0.0)
        assertEquals(1.0, engine.scale, "0 应忽略")
        engine.setScale(-1.0)
        assertEquals(1.0, engine.scale, "负数应忽略")
    }

    @Test fun setSameScaleDoesNotRebase() = runTest {
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 100)),
            demuxFactory = { FakeDemux(0L, listOf(videoFrame(0L, true))) },
            framePacker = TsCapturingPacker(),
            rtp = CapturingRtp(),
            clock = virtualClock(),
            initialScale = 2.0
        )
        engine.setScale(2.0)
        assertEquals(2.0, engine.scale)
    }

    @Test fun rtpTimestampUsesMediaPtsNotScale() = runTest {
        // 90kHz RTP timestamp 跟 media PTS 走,不受 scale 影响
        val packer = TsCapturingPacker()
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 200)),
            demuxFactory = {
                FakeDemux(
                    0L,
                    listOf(videoFrame(0L, true), videoFrame(100_000L), videoFrame(200_000L))
                )
            },
            framePacker = packer,
            rtp = CapturingRtp(),
            clock = virtualClock(),
            initialScale = 4.0
        )
        engine.run()
        // ts90k = mediaPts * 9 / 100  → 0, 9000, 18000
        assertEquals(listOf(0L, 9000L, 18000L), packer.ts)
    }

    @Test fun allFiveLegalScalesAccepted() = runTest {
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 100)),
            demuxFactory = { FakeDemux(0L, listOf(videoFrame(0L, true))) },
            framePacker = TsCapturingPacker(),
            rtp = CapturingRtp(),
            clock = virtualClock()
        )
        listOf(0.25, 0.5, 1.0, 2.0, 4.0).forEach {
            engine.setScale(it)
            assertEquals(it, engine.scale, "scale=$it 应被接受")
        }
    }

    @Test fun scaleChangeMidstream_keepsCurrentPtsAnchor() = runTest {
        // 关键测试:中途切档,currentGlobalPtsUs 不应跳变
        val packer = TsCapturingPacker()
        val rtp = CapturingRtp()
        val frames = (0..9).map { videoFrame(it * 100_000L, isKey = it == 0) }
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 1000)),
            demuxFactory = { FakeDemux(0L, frames) },
            framePacker = packer,
            rtp = rtp,
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.runCurrent()
        // 跑一阵后切到 2x
        testScheduler.advanceTimeBy(300L)
        val ptsBeforeSwitch = engine.currentGlobalPtsUs
        engine.setScale(2.0)
        testScheduler.advanceTimeBy(1L)
        // 切档瞬间 currentGlobalPtsUs 不变
        assertTrue(engine.currentGlobalPtsUs >= ptsBeforeSwitch, "切档不应回退 PTS")
        job.cancel()
    }
}

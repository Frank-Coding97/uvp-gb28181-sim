package com.uvp.sim.recording

import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.H264Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineTest {

    private class TestClock(start: Long = 0L) : PlaybackClock {
        var ms: Long = start
        override fun nowMs(): Long = ms
    }

    private class CapturingRtp : RtpSink {
        val packets = mutableListOf<ByteArray>()
        var closed = false
        override suspend fun send(packet: ByteArray) { packets += packet }
        override suspend fun close() { closed = true }
    }

    /** Fake demux:不读真文件,直接吐 caller 提供的帧序列。 */
    private class FakeDemux(
        firstPts: Long,
        private val frames: List<MediaFrame>
    ) : Mp4DemuxSource {
        override val firstFramePtsUs: Long = firstPts
        var opened = false
        var closed = false
        override suspend fun open(): Result<Unit> { opened = true; return Result.success(Unit) }
        override fun frames(): Flow<MediaFrame> = flow { frames.forEach { emit(it) } }
        override suspend fun close() { closed = true }
    }

    /** 抓 ts90k 的 packer 包装,把每个 video 帧的 ts 记下来。 */
    private class TsCapturingPacker : FramePacker {
        val timestamps = mutableListOf<Long>()
        override fun packVideo(frame: H264Frame, timestamp90k: Long): List<ByteArray> {
            timestamps += timestamp90k
            return listOf(byteArrayOf(0x00))  // 占位 1 包/帧
        }
        override fun packAudio(frame: AudioFrame, timestamp90k: Long): List<ByteArray> {
            timestamps += timestamp90k
            return listOf(byteArrayOf(0x01))
        }
    }

    private fun seg(id: String, startMs: Long, durationMs: Long) = RecordingFile(
        id = id,
        startTimeMs = startMs,
        endTimeMs = startMs + durationMs,
        durationMs = durationMs,
        channelId = "ch1",
        filePath = "/tmp/$id.mp4",
        sizeBytes = 1024L
    )

    private fun videoFrame(ptsUs: Long, isKey: Boolean = false) =
        MediaFrame.Video(
            timestampUs = ptsUs,
            nalUnits = listOf(byteArrayOf(0x65, 0x88.toByte(), 0x00)),
            isKeyframe = isKey
        )

    @Test fun emptySegments_callsOnComplete_noFramesSent() = runTest {
        var done = false
        val rtp = CapturingRtp()
        val engine = PlaybackEngine(
            segments = emptyList(),
            demuxFactory = { error("should not be called") },
            framePacker = TsCapturingPacker(),
            rtp = rtp,
            clock = TestClock(),
            onComplete = { done = true }
        )
        engine.run()
        assertTrue(done)
        assertEquals(0, rtp.packets.size)
        assertEquals(0, engine.frameCount)
    }

    @Test fun singleSegment_emitsAllFrames_demuxClosed() = runTest {
        val frames = listOf(
            videoFrame(0L, isKey = true),
            videoFrame(40_000L),
            videoFrame(80_000L)
        )
        var done = false
        val rtp = CapturingRtp()
        val demuxes = mutableListOf<FakeDemux>()
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 1_000, 200)),
            demuxFactory = { _ ->
                FakeDemux(firstPts = 0L, frames = frames).also { demuxes += it }
            },
            framePacker = TsCapturingPacker(),
            rtp = rtp,
            clock = TestClock(),
            onComplete = { done = true }
        )
        engine.run()
        assertTrue(done)
        assertEquals(3, engine.frameCount)
        assertEquals(3, rtp.packets.size)
        assertTrue(demuxes[0].closed, "demux 必须 close")
    }

    @Test fun multiSegment_globalPtsIsMonotonic_acrossSegments() = runTest {
        // 段 1:demux 输出 PTS 0us, 40_000us(2 帧), duration 100ms
        // 段 2:demux 也从 0 重新开始, 必须被平移到 100_000us+
        val seg1Frames = listOf(videoFrame(0L, isKey = true), videoFrame(40_000L))
        val seg2Frames = listOf(videoFrame(0L, isKey = true), videoFrame(40_000L))
        val packer = TsCapturingPacker()
        val engine = PlaybackEngine(
            segments = listOf(
                seg("a", startMs = 1_000, durationMs = 100),
                seg("b", startMs = 5_000, durationMs = 100)
            ),
            demuxFactory = { path ->
                if (path.endsWith("a.mp4")) FakeDemux(0L, seg1Frames)
                else FakeDemux(0L, seg2Frames)
            },
            framePacker = packer,
            rtp = CapturingRtp(),
            clock = TestClock()
        )
        engine.run()
        assertEquals(4, engine.frameCount)
        val ts = packer.timestamps
        assertEquals(4, ts.size)
        for (i in 1 until ts.size) {
            assertTrue(
                ts[i] > ts[i - 1],
                "ts 应单调递增, 实际 = $ts (i=$i)"
            )
        }
        assertEquals(0L, ts[0])
        // 段 2 第 1 帧 globalPts = 100_000us = 段 1 duration,ts90k = 100_000 * 9 / 100 = 9000
        assertEquals(9000L, ts[2])
    }

    @Test fun firstFramePtsOffset_isSubtracted() {
        // demux 报告 firstFramePtsUs = 1_000_000(1 秒),后续 PTS 从 1_000_000 起
        // engine 应当以这个为锚归零,使 ts90k 从 0 开始
        val packer = TsCapturingPacker()
        kotlinx.coroutines.test.runTest {
            val engine = PlaybackEngine(
                segments = listOf(seg("a", 1_000, 100)),
                demuxFactory = {
                    FakeDemux(
                        firstPts = 1_000_000L,
                        frames = listOf(
                            videoFrame(1_000_000L, true),
                            videoFrame(1_040_000L)
                        )
                    )
                },
                framePacker = packer,
                rtp = CapturingRtp(),
                clock = TestClock()
            )
            engine.run()
            assertEquals(0L, packer.timestamps[0], "firstPts 应当作 0 锚")
            assertEquals(40_000L * 9 / 100, packer.timestamps[1])  // 3600
        }
    }

    @Test fun throttling_honorsClock_doesNotBunchUp() = runTest {
        // 帧间隔 100ms。runTest 虚拟 delay 后 testScheduler.currentTime 应 >= 200ms
        val frames = listOf(
            videoFrame(0L, isKey = true),
            videoFrame(100_000L),
            videoFrame(200_000L)
        )
        val rtp = CapturingRtp()
        val scheduler = testScheduler
        val clock = object : PlaybackClock {
            override fun nowMs(): Long = scheduler.currentTime
        }
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 1_000, 300)),
            demuxFactory = { FakeDemux(0L, frames) },
            framePacker = TsCapturingPacker(),
            rtp = rtp,
            clock = clock
        )
        engine.run()
        assertEquals(3, engine.frameCount)
        assertTrue(scheduler.currentTime >= 200, "delay 累计应 >= 200ms, actual=${scheduler.currentTime}")
    }

    @Test fun openFailure_propagatesAsPlaybackError() = runTest {
        val failingDemux = object : Mp4DemuxSource {
            override val firstFramePtsUs = 0L
            override suspend fun open() = Result.failure<Unit>(RuntimeException("file missing"))
            override fun frames() = flow<MediaFrame> {}
            override suspend fun close() {}
        }
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 1_000, 100)),
            demuxFactory = { failingDemux },
            framePacker = TsCapturingPacker(),
            rtp = CapturingRtp(),
            clock = TestClock()
        )
        assertFails { engine.run() }
    }

    @Test fun onComplete_calledAfterLastFrame() = runTest {
        val callOrder = mutableListOf<String>()
        val rtp = object : RtpSink {
            override suspend fun send(packet: ByteArray) { callOrder += "send" }
            override suspend fun close() {}
        }
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 1_000, 100)),
            demuxFactory = { FakeDemux(0L, listOf(videoFrame(0L, true))) },
            framePacker = TsCapturingPacker(),
            rtp = rtp,
            clock = TestClock(),
            onComplete = { callOrder += "complete" }
        )
        engine.run()
        assertTrue(callOrder.first() == "send")
        assertEquals("complete", callOrder.last())
    }
}

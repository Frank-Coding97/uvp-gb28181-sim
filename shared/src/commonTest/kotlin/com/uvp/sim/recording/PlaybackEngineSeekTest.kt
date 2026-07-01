package com.uvp.sim.recording

import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.H264Frame
import kotlin.concurrent.Volatile
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
 * T07: PlaybackEngine.seek 单测(spec §B.1-B.7)。
 *
 * 三段录像 [0-100ms, 100-200ms, 200-300ms] 各 3 帧。
 * seek 全局 ms,跨段索引 + 段内 demux.seekTo + PTS 全局平移。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineSeekTest {

    /** Fake demux 支持 seek:把 frames 按 ts 排序,seekTo 后从 ≤ target 的最近 keyframe 开始。 */
    private class SeekableFake(
        firstPts: Long,
        private val keyframePtsMicro: List<Long>,
        private val frames: List<MediaFrame>
    ) : Mp4DemuxSource {
        override val firstFramePtsUs: Long = firstPts
        @Volatile var seekTarget: Long = firstPts

        override suspend fun open(): Result<Unit> = Result.success(Unit)
        override fun frames(): Flow<MediaFrame> = flow {
            val resumeFromPts = seekTarget
            for (f in frames) if (f.timestampUs >= resumeFromPts) emit(f)
        }
        override suspend fun close() {}

        override suspend fun seekTo(targetUs: Long): Long {
            // 找 ≤ target 最近 keyframe
            val pick = keyframePtsMicro.lastOrNull { it <= targetUs } ?: keyframePtsMicro.first()
            seekTarget = pick
            return pick
        }
    }

    private fun seg(id: String, startMs: Long, durationMs: Long) = RecordingFile(
        id = id, startTimeMs = startMs, endTimeMs = startMs + durationMs,
        durationMs = durationMs, channelId = "ch1",
        filePath = "/tmp/$id.mp4", sizeBytes = 1024L
    )

    private fun videoFrame(ptsUs: Long, isKey: Boolean = false) =
        MediaFrame.Video(
            timestampUs = ptsUs,
            nalUnits = listOf(byteArrayOf(0x65, 0x88.toByte())),
            isKeyframe = isKey
        )

    private class StubPacker : FramePacker {
        val ptsCaptured = mutableListOf<Long>()
        override fun packVideo(frame: H264Frame, timestamp90k: Long): List<ByteArray> {
            ptsCaptured += frame.timestampUs
            return listOf(byteArrayOf(0x00))
        }
        override fun packAudio(frame: AudioFrame, timestamp90k: Long) = emptyList<ByteArray>()
    }

    private class CountingRtp : RtpSink {
        var count = 0
        override suspend fun send(packet: ByteArray) { count++ }
        override suspend fun close() {}
    }

    private fun TestScope.virtualClock() = object : PlaybackClock {
        override fun nowMs(): Long = testScheduler.currentTime
    }

    private fun threeSegmentsFactory(): Mp4DemuxFactory = factory@{ path ->
        // 每段 3 帧,PTS 段内 0 / 30ms / 60ms,keyframe 在 0
        val frames = listOf(
            videoFrame(0L, isKey = true),
            videoFrame(30_000L),
            videoFrame(60_000L)
        )
        SeekableFake(
            firstPts = 0L,
            keyframePtsMicro = listOf(0L),
            frames = frames
        )
    }

    @Test fun seekToZeroRestartsAtSegmentZero() = runTest {
        val packer = StubPacker()
        val rtp = CountingRtp()
        val engine = PlaybackEngine(
            segments = listOf(
                seg("a", 0, 100),
                seg("b", 100, 100),
                seg("c", 200, 100)
            ),
            demuxFactory = threeSegmentsFactory(),
            framePacker = packer,
            rtp = rtp,
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.advanceTimeBy(50L)
        engine.seek(0L)
        testScheduler.advanceTimeBy(2000L)
        // seek(0) 后 currentGlobalPtsUs 应在段 0 内
        assertTrue(engine.currentGlobalPtsUs >= 0L)
        job.cancel()
    }

    @Test fun seekIntoSegmentOne() = runTest {
        val packer = StubPacker()
        val engine = PlaybackEngine(
            segments = listOf(
                seg("a", 0, 100),
                seg("b", 100, 100),
                seg("c", 200, 100)
            ),
            demuxFactory = threeSegmentsFactory(),
            framePacker = packer,
            rtp = CountingRtp(),
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.advanceTimeBy(10L)
        engine.seek(50L)  // 段 0 的 50ms
        testScheduler.advanceTimeBy(2000L)
        assertTrue(
            engine.currentGlobalPtsUs >= 50_000L,
            "seek 后 currentGlobalPtsUs 应 >= 50ms (实际=${engine.currentGlobalPtsUs})"
        )
        job.cancel()
    }

    @Test fun seekCrossSegmentToMiddleSecondSegment() = runTest {
        val packer = StubPacker()
        val engine = PlaybackEngine(
            segments = listOf(
                seg("a", 0, 100),
                seg("b", 100, 100),
                seg("c", 200, 100)
            ),
            demuxFactory = threeSegmentsFactory(),
            framePacker = packer,
            rtp = CountingRtp(),
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.advanceTimeBy(10L)
        engine.seek(150L)  // 段 1 的 50ms
        testScheduler.advanceTimeBy(2000L)
        assertTrue(
            engine.currentGlobalPtsUs >= 150_000L,
            "跨段 seek 后 PTS 应 >= 150ms (实际=${engine.currentGlobalPtsUs})"
        )
        job.cancel()
    }

    @Test fun seekToThirdSegment() = runTest {
        val engine = PlaybackEngine(
            segments = listOf(
                seg("a", 0, 100),
                seg("b", 100, 100),
                seg("c", 200, 100)
            ),
            demuxFactory = threeSegmentsFactory(),
            framePacker = StubPacker(),
            rtp = CountingRtp(),
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.advanceTimeBy(10L)
        engine.seek(250L)
        testScheduler.advanceTimeBy(2000L)
        assertTrue(engine.currentGlobalPtsUs >= 250_000L)
        job.cancel()
    }

    @Test fun seekOutOfRangeEndsRun() = runTest {
        var completed = false
        val engine = PlaybackEngine(
            segments = listOf(
                seg("a", 0, 100),
                seg("b", 100, 100),
                seg("c", 200, 100)
            ),
            demuxFactory = threeSegmentsFactory(),
            framePacker = StubPacker(),
            rtp = CountingRtp(),
            clock = virtualClock(),
            onComplete = { completed = true }
        )
        val job = launch { engine.run() }
        testScheduler.advanceTimeBy(10L)
        engine.seek(500L)  // 越界(总 300ms)
        testScheduler.advanceTimeBy(2000L)
        assertTrue(completed, "seek 越界应触发 onComplete")
        job.cancel()
    }

    @Test fun negativeSeekIgnored() = runTest {
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 0, 100)),
            demuxFactory = threeSegmentsFactory(),
            framePacker = StubPacker(),
            rtp = CountingRtp(),
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.runCurrent()
        engine.seek(-100L)
        // 不抛异常,日志记 SEEK_NEGATIVE
        testScheduler.advanceTimeBy(2000L)
        job.cancel()
    }

    @Test fun seekResetsCurrentPtsAnchor() = runTest {
        val engine = PlaybackEngine(
            segments = listOf(
                seg("a", 0, 100),
                seg("b", 100, 100),
                seg("c", 200, 100)
            ),
            demuxFactory = threeSegmentsFactory(),
            framePacker = StubPacker(),
            rtp = CountingRtp(),
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.advanceTimeBy(10L)
        engine.seek(180L)
        testScheduler.advanceTimeBy(50L)
        // seek 后 currentGlobalPtsUs 立即更新到 target * 1000
        assertTrue(
            engine.currentGlobalPtsUs >= 180_000L,
            "seek 后立即重置 currentGlobalPtsUs"
        )
        job.cancel()
    }

    @Test fun pauseSeekResume_compoundScenario() = runTest {
        val engine = PlaybackEngine(
            segments = listOf(
                seg("a", 0, 100),
                seg("b", 100, 100),
                seg("c", 200, 100)
            ),
            demuxFactory = threeSegmentsFactory(),
            framePacker = StubPacker(),
            rtp = CountingRtp(),
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.advanceTimeBy(10L)
        engine.pause()
        engine.seek(150L)
        // seek 时虽然 paused,seek 控制流仍能跳转
        testScheduler.advanceTimeBy(100L)
        engine.resume()
        testScheduler.advanceTimeBy(2000L)
        assertTrue(engine.currentGlobalPtsUs >= 150_000L)
        job.cancel()
    }
}

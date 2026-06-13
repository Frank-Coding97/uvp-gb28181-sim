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
 * T04: PlaybackEngine.pause/resume 单测(spec §C.2-C.4 / C.9)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEnginePauseTest {

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

    private class StubPacker : FramePacker {
        override fun packVideo(frame: H264Frame, timestamp90k: Long) = listOf(byteArrayOf(0x00))
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

    @Test fun pauseStopsRtpEmission() = runTest {
        // 帧间隔 100ms,十帧。pause 后不应继续推流。
        val rtp = CountingRtp()
        val frames = (0..9).map { videoFrame(it * 100_000L, isKey = it == 0) }
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 1000)),
            demuxFactory = { FakeDemux(0L, frames) },
            framePacker = StubPacker(),
            rtp = rtp,
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        // 先让 pause 在虚拟时间推进前生效
        engine.pause()
        testScheduler.advanceTimeBy(2000L)
        testScheduler.runCurrent()
        // 第 0 帧 targetWall=0 可能在 pause 前已推出,但后续 9 帧应被挡
        assertTrue(rtp.count <= 1, "pause 应阻止后续帧 (count=${rtp.count})")
        job.cancel()
    }

    @Test fun pauseThenResumeContinuesStream() = runTest {
        val rtp = CountingRtp()
        val frames = listOf(
            videoFrame(0L, true),
            videoFrame(100_000L),
            videoFrame(200_000L)
        )
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 300)),
            demuxFactory = { FakeDemux(0L, frames) },
            framePacker = StubPacker(),
            rtp = rtp,
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(50L)
        engine.pause()
        testScheduler.advanceTimeBy(500L)
        engine.resume()
        // resume 后帧循环应继续到末尾
        testScheduler.advanceTimeBy(2000L)
        assertEquals(3, rtp.count, "resume 后应推完所有 3 帧")
        job.cancel()
    }

    @Test fun pauseFreezesCurrentPts() = runTest {
        val frames = listOf(videoFrame(0L, true), videoFrame(100_000L), videoFrame(200_000L))
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 300)),
            demuxFactory = { FakeDemux(0L, frames) },
            framePacker = StubPacker(),
            rtp = CountingRtp(),
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(150L)
        engine.pause()
        val frozen = engine.currentGlobalPtsUs
        testScheduler.advanceTimeBy(1000L)
        assertEquals(frozen, engine.currentGlobalPtsUs, "pause 期间 currentGlobalPtsUs 不变")
        job.cancel()
    }

    @Test fun pauseIsIdempotent() {
        val engine = newIdleEngine()
        engine.pause()
        engine.pause()
        engine.pause()
        assertTrue(engine.paused, "重复 pause 应仍是 paused")
    }

    @Test fun resumeOnUnpausedIsIdempotent() {
        val engine = newIdleEngine()
        engine.resume()
        assertTrue(!engine.paused, "未 pause resume 应无副作用")
    }

    @Test fun pauseSetScaleResume_compoundScenario() = runTest {
        val rtp = CountingRtp()
        val frames = (0..4).map { videoFrame(it * 100_000L, isKey = it == 0) }
        val engine = PlaybackEngine(
            segments = listOf(seg("a", 500)),
            demuxFactory = { FakeDemux(0L, frames) },
            framePacker = StubPacker(),
            rtp = rtp,
            clock = virtualClock()
        )
        val job = launch { engine.run() }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(50L)
        engine.pause()
        engine.setScale(2.0)
        testScheduler.advanceTimeBy(200L)
        engine.resume()
        testScheduler.advanceTimeBy(2000L)
        assertEquals(5, rtp.count, "pause + setScale + resume 后应正常推完")
        assertEquals(2.0, engine.scale, "scale 切换应保留")
        job.cancel()
    }

    @Test fun pauseBeforeRunDoesNotThrow() = runTest {
        val engine = newIdleEngine()
        engine.pause()
        // 应能正常处于 paused 状态
        assertTrue(engine.paused)
    }

    private fun newIdleEngine(): PlaybackEngine = PlaybackEngine(
        segments = listOf(seg("a", 100)),
        demuxFactory = { FakeDemux(0L, listOf(videoFrame(0L, true))) },
        framePacker = StubPacker(),
        rtp = CountingRtp(),
        clock = object : PlaybackClock { override fun nowMs() = 0L }
    )
}

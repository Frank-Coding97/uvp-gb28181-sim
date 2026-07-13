package com.uvp.sim.recording

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T05: Mp4DemuxSource.seekTo 接口契约测试。
 * 用 fake 实现验证 SEEK_TO_PREVIOUS_SYNC 语义(向前找最近 keyframe)。
 */
class Mp4DemuxSourceSeekTest {

    private fun videoFrame(ptsUs: Long, isKey: Boolean = false) =
        MediaFrame.Video(
            timestampUs = ptsUs,
            nalUnits = listOf(byteArrayOf(0x65, 0x88.toByte())),
            isKeyframe = isKey
        )

    /** SEEK_TO_PREVIOUS_SYNC 语义的 fake demux。 */
    private class SeekableFake(
        firstPts: Long,
        private val keyframePts: List<Long>,
        private val frames: List<MediaFrame>
    ) : Mp4DemuxSource {
        override val firstFramePtsUs: Long = firstPts
        @Volatile var seekTarget: Long = firstPts

        override suspend fun open(): Result<Unit> = Result.success(Unit)
        override fun frames(): Flow<MediaFrame> = flow {
            for (f in frames) if (f.timestampUs >= seekTarget) emit(f)
        }
        override suspend fun close() {}
        override suspend fun seekTo(targetUs: Long): Long {
            val pick = keyframePts.lastOrNull { it <= targetUs } ?: keyframePts.first()
            seekTarget = pick
            return pick
        }
    }

    private fun makeFake(): SeekableFake {
        // 5 帧:0(key) / 30 / 60(key) / 90 / 120 (us 单位 1000 倍 → ms)
        val keyframes = listOf(0L, 60_000L)
        val frames = listOf(
            videoFrame(0L, isKey = true),
            videoFrame(30_000L),
            videoFrame(60_000L, isKey = true),
            videoFrame(90_000L),
            videoFrame(120_000L)
        )
        return SeekableFake(0L, keyframes, frames)
    }

    @Test fun seekToZeroReturnsFirstPts() = runTest {
        val d = makeFake()
        d.open()
        val pts = d.seekTo(0L)
        assertEquals(0L, pts)
    }

    @Test fun seekToKeyframePtsExact() = runTest {
        val d = makeFake()
        d.open()
        val pts = d.seekTo(60_000L)
        assertEquals(60_000L, pts)
    }

    @Test fun seekToNonKeyframeFallsBackToPreviousSync() = runTest {
        val d = makeFake()
        d.open()
        // 90_000 不是 keyframe,应回退到 60_000(最近 ≤ target 的 keyframe)
        val pts = d.seekTo(90_000L)
        assertEquals(60_000L, pts)
    }

    @Test fun seekBeyondEndReturnsLastKeyframe() = runTest {
        val d = makeFake()
        d.open()
        val pts = d.seekTo(200_000L)
        assertEquals(60_000L, pts, "超出末尾应返回最后 keyframe")
    }

    @Test fun framesAfterSeekStartFromActualPts() = runTest {
        val d = makeFake()
        d.open()
        d.seekTo(75_000L)
        val collected = d.frames().toList()
        assertTrue(collected.isNotEmpty())
        assertEquals(60_000L, collected.first().timestampUs, "seek 后第一帧应 == seekTo 返回值")
    }

    @Test fun defaultSeekToOnInterfaceIsNoOp() = runTest {
        // Mp4DemuxSource 默认实现:seekTo 返 firstFramePtsUs(向后兼容旧 fake)
        val plain = object : Mp4DemuxSource {
            override val firstFramePtsUs = 12345L
            override suspend fun open() = Result.success(Unit)
            override fun frames(): Flow<MediaFrame> = flow {}
            override suspend fun close() {}
        }
        plain.open()
        assertEquals(12345L, plain.seekTo(99_999L), "默认实现应返 firstFramePtsUs")
    }
}

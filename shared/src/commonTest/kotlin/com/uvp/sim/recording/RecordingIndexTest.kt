package com.uvp.sim.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingIndexTest {

    private fun sample(
        id: String,
        startMs: Long,
        endMs: Long,
        channelId: String = "34020000001320000001",
        source: RecordSource = RecordSource.Manual,
        type: RecordType = RecordType.Time
    ) = RecordingFile(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        durationMs = endMs - startMs,
        channelId = channelId,
        filePath = "/tmp/$id.mp4",
        sizeBytes = 1024L,
        thumbnailPath = "/tmp/$id.jpg",
        source = source,
        type = type,
        secrecy = 0
    )

    @Test fun decode_emptyJson_returnsDefault() {
        val idx = RecordingIndex.decode("")
        assertEquals(1, idx.version)
        assertTrue(idx.files.isEmpty())
    }

    @Test fun decode_blankJson_returnsDefault() {
        val idx = RecordingIndex.decode("   \n  ")
        assertTrue(idx.files.isEmpty())
    }

    @Test fun roundtrip_threeFiles_preservesAllFields() {
        val original = RecordingIndexFile(
            files = listOf(
                sample("a", 1_000, 2_000, source = RecordSource.Manual, type = RecordType.Time),
                sample("b", 3_000, 4_000, source = RecordSource.PlatformCmd, type = RecordType.Alarm),
                sample("c", 5_000, 6_000, channelId = "ch2", type = RecordType.Manual_)
            )
        )
        val json = RecordingIndex.encode(original)
        val decoded = RecordingIndex.decode(json)
        assertEquals(original, decoded)
        // 字段保真核对
        assertEquals(RecordSource.PlatformCmd, decoded.files[1].source)
        assertEquals(RecordType.Alarm, decoded.files[1].type)
        assertEquals("/tmp/a.jpg", decoded.files[0].thumbnailPath)
    }

    @Test fun append_keepsExisting_addsNew() {
        val idx = RecordingIndexFile(files = listOf(sample("a", 1_000, 2_000)))
        val updated = RecordingIndex.append(idx, sample("b", 3_000, 4_000))
        assertEquals(2, updated.files.size)
        assertEquals("a", updated.files[0].id)
        assertEquals("b", updated.files[1].id)
        // 原对象未被修改(不可变)
        assertEquals(1, idx.files.size)
    }

    @Test fun remove_byId_dropsTarget() {
        val idx = RecordingIndexFile(
            files = listOf(
                sample("a", 1_000, 2_000),
                sample("b", 3_000, 4_000),
                sample("c", 5_000, 6_000)
            )
        )
        val updated = RecordingIndex.remove(idx, "b")
        assertEquals(2, updated.files.size)
        assertNull(updated.files.firstOrNull { it.id == "b" })
    }

    @Test fun remove_missingId_isNoop() {
        val idx = RecordingIndexFile(files = listOf(sample("a", 1_000, 2_000)))
        val updated = RecordingIndex.remove(idx, "nonexistent")
        assertEquals(idx, updated)
    }

    @Test fun decode_corruptedJson_returnsEmpty() {
        // 防 crash:JSON 格式坏 / 字段缺 / 类型错误,都退化空索引
        val idx = RecordingIndex.decode("{not json at all")
        assertTrue(idx.files.isEmpty())
        assertEquals(1, idx.version)
    }

    @Test fun decode_unknownFields_isTolerant() {
        // 老版本写,新版本读;反之亦然
        val json = """{"version":1,"files":[],"future_field":"ignored"}"""
        val idx = RecordingIndex.decode(json)
        assertNotNull(idx)
        assertTrue(idx.files.isEmpty())
    }

    @Test fun queryByTimeRange_overlapBoundary_inclusive() {
        // 命中规则:start <= file.endTimeMs AND end >= file.startTimeMs
        val idx = RecordingIndexFile(
            files = listOf(
                sample("a", 1_000, 2_000),
                sample("b", 2_000, 3_000),  // 边界完全相邻
                sample("c", 5_000, 6_000)
            )
        )
        val hits = RecordingIndex.queryByTimeRange(idx, 2_000, 2_000)
        // 时间点 2000 同时落在 a 的 endTime 和 b 的 startTime,两条都该命中
        assertEquals(2, hits.size)
    }

    @Test fun queryByTimeRange_partialOverlap_hits() {
        val idx = RecordingIndexFile(
            files = listOf(
                sample("a", 1_000, 5_000)
            )
        )
        // 查询区间 [3000, 7000] 部分覆盖 [1000, 5000] — 命中
        assertEquals(1, RecordingIndex.queryByTimeRange(idx, 3_000, 7_000).size)
        // 查询区间 [6000, 7000] 不重叠 — 不命中
        assertEquals(0, RecordingIndex.queryByTimeRange(idx, 6_000, 7_000).size)
    }

    @Test fun queryByTimeRange_channelIdFilter_filtersOut() {
        val idx = RecordingIndexFile(
            files = listOf(
                sample("a", 1_000, 5_000, channelId = "ch1"),
                sample("b", 2_000, 4_000, channelId = "ch2")
            )
        )
        val hits = RecordingIndex.queryByTimeRange(idx, 0, 10_000, channelId = "ch1")
        assertEquals(1, hits.size)
        assertEquals("a", hits[0].id)
    }

    @Test fun queryByTimeRange_typeFilter_filtersOut() {
        val idx = RecordingIndexFile(
            files = listOf(
                sample("a", 1_000, 5_000, type = RecordType.Time),
                sample("b", 2_000, 4_000, type = RecordType.Alarm)
            )
        )
        val hits = RecordingIndex.queryByTimeRange(idx, 0, 10_000, type = RecordType.Alarm)
        assertEquals(1, hits.size)
        assertEquals("b", hits[0].id)
    }

    @Test fun queryByTimeRange_resultsSortedByStartTimeAsc() {
        // PLAYBACK 拼接需要按时间升序
        val idx = RecordingIndexFile(
            files = listOf(
                sample("c", 5_000, 6_000),
                sample("a", 1_000, 2_000),
                sample("b", 3_000, 4_000)
            )
        )
        val hits = RecordingIndex.queryByTimeRange(idx, 0, 10_000)
        assertEquals(listOf("a", "b", "c"), hits.map { it.id })
    }
}

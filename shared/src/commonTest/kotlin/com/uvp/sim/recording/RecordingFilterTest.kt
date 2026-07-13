package com.uvp.sim.recording

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingFilterTest {

    private fun file(id: String, startMs: Long, endMs: Long, channel: String = "ch1") =
        RecordingFile(
            id = id,
            startTimeMs = startMs,
            endTimeMs = endMs,
            durationMs = endMs - startMs,
            channelId = channel,
            filePath = "/tmp/$id.mp4",
            sizeBytes = 1024L
        )

    @Test fun defaults_thisWeek_atFridayCovers7Days() {
        // 2026-06-12 22:00 +0800 = 周五
        val now = Instant.parse("2026-06-12T22:00:00+08:00")
        val tz = TimeZone.of("Asia/Shanghai")
        val filter = RecordingFilter.Defaults.thisWeek(now, tz)
        // 区间精度按天:跨度 7 天(end - start = 7 * 86400 * 1000 - 1 ms 或近似)
        val spanMs = filter.endMs - filter.startMs
        // 允许 ±1 天宽容(具体周一/周日切边由实现决定),但不能跨多周
        assertTrue(spanMs in (5L * 86_400_000L)..(8L * 86_400_000L), "spanMs=$spanMs")
        assertTrue(filter.channelKeyword.isEmpty())
    }

    @Test fun apply_keywordCaseInsensitive_matchesChannel() {
        val files = listOf(
            file("a", 1_000, 2_000, "Camera-Front"),
            file("b", 1_000, 2_000, "camera-rear"),
            file("c", 1_000, 2_000, "Mic-1")
        )
        val filter = RecordingFilter(startMs = 0, endMs = 10_000, channelKeyword = "camera")
        val hits = filter.apply(files)
        assertEquals(2, hits.size)
        assertEquals(setOf("a", "b"), hits.map { it.id }.toSet())
    }

    @Test fun apply_emptyKeyword_returnsAll() {
        val files = listOf(
            file("a", 1_000, 2_000),
            file("b", 1_000, 2_000)
        )
        val filter = RecordingFilter(startMs = 0, endMs = 10_000, channelKeyword = "")
        assertEquals(2, filter.apply(files).size)
    }

    @Test fun apply_outOfRange_filtered() {
        val files = listOf(
            file("a", 1_000, 2_000),
            file("b", 5_000, 6_000)
        )
        val filter = RecordingFilter(startMs = 4_000, endMs = 7_000)
        val hits = filter.apply(files)
        assertEquals(listOf("b"), hits.map { it.id })
    }
}

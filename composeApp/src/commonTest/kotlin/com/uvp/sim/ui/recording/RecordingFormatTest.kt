package com.uvp.sim.ui.recording

import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordType
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.ui.model.RecordingFileDto
import com.uvp.sim.ui.model.mapper.toDto
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingFormatTest {

    @Test
    fun buildRecordingListModel_filters_groups_and_sums() {
        val tz = TimeZone.of("Asia/Shanghai")
        val files = listOf(
            file("a", startMs = 1_750_000_000_000L, endMs = 1_750_000_030_000L, channelId = "ch-1", sizeBytes = 1024L),
            file("b", startMs = 1_750_086_400_000L, endMs = 1_750_086_460_000L, channelId = "ch-2", sizeBytes = 2048L),
            file("c", startMs = 1_750_086_800_000L, endMs = 1_750_086_860_000L, channelId = "ch-1", sizeBytes = 4096L),
        )

        val filter = RecordingFilter(
            startMs = 1_750_086_300_000L,
            endMs = 1_750_086_900_000L,
            channelKeyword = "ch-1",
        )

        val model = buildRecordingListModel(files, filter, tz)

        assertFalse(model.isEmpty)
        assertEquals(1, model.count)
        assertEquals(4_096L, model.totalBytes)
        assertEquals(60_000L, model.totalDurationMs)
        assertEquals(1, model.groupedFiles.size)
        assertTrue(model.groupedFiles.all { it.second.all { file -> file.channelId == "ch-1" } })
    }

    @Test
    fun buildRecordingListModel_withoutFilter_keeps_original_files() {
        val tz = TimeZone.of("Asia/Shanghai")
        val files = listOf(
            file("a", startMs = 1_750_000_000_000L, endMs = 1_750_000_030_000L),
            file("b", startMs = 1_750_000_040_000L, endMs = 1_750_000_100_000L),
        )

        val model = buildRecordingListModel(files, null, tz)

        assertEquals(2, model.count)
        assertEquals(files, model.filteredFiles)
        assertEquals(2, model.groupedFiles.sumOf { it.second.size })
    }

    private fun file(
        id: String,
        startMs: Long,
        endMs: Long,
        channelId: String = "channel",
        sizeBytes: Long = 1024L,
    ): RecordingFileDto = RecordingFile(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        durationMs = endMs - startMs,
        channelId = channelId,
        filePath = "/tmp/$id.mp4",
        sizeBytes = sizeBytes,
        thumbnailPath = "/tmp/$id.jpg",
        source = RecordSource.Manual,
        type = RecordType.Time,
    ).toDto()
}

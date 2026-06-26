package com.uvp.sim.ui.model.mapper

import com.uvp.sim.recording.RecordType
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.recording.RecordingState
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.ui.model.RecordSourceDto
import com.uvp.sim.ui.model.RecordingStateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecordingMapperTest {

    @Test
    fun recordSource_all_entries_map() {
        RecordSource.entries.forEach { assertEquals(it.name, it.toDto().name) }
        assertEquals(RecordSource.entries.size, RecordSourceDto.entries.size)
    }

    @Test
    fun recordingFile_full_field_mapping() {
        val domain = RecordingFile(
            id = "f-1",
            startTimeMs = 1000L,
            endTimeMs = 2000L,
            durationMs = 1000L,
            channelId = "ch-1",
            filePath = "/tmp/x.mp4",
            sizeBytes = 100L,
            thumbnailPath = "/tmp/x.jpg",
            source = RecordSource.Manual,
            type = RecordType.Time,
            secrecy = 0,
        )
        val dto = domain.toDto()
        assertEquals("f-1", dto.id)
        assertEquals(1000L, dto.startTimeMs)
        assertEquals("ch-1", dto.channelId)
        assertEquals(RecordSourceDto.Manual, dto.source)
        assertEquals(RecordType.Time, dto.type)  // B 档 api/, 直传
    }

    @Test
    fun recordingFilter_three_field_mapping() {
        val dto = RecordingFilter(1000L, 2000L, "kw").toDto()
        assertEquals(1000L, dto.startMs)
        assertEquals(2000L, dto.endMs)
        assertEquals("kw", dto.channelKeyword)
    }

    @Test
    fun recordingState_idle_maps_to_dto_idle() {
        assertEquals(RecordingStateDto.Idle, (RecordingState.Idle as RecordingState).toDto())
    }

    @Test
    fun recordingState_recording_preserves_fields() {
        val dto = RecordingState.Recording(1000L, 5, RecordSource.PlatformCmd).toDto()
        assertIs<RecordingStateDto.Recording>(dto)
        assertEquals(1000L, dto.startMs)
        assertEquals(5, dto.segmentIndex)
        assertEquals(RecordSourceDto.PlatformCmd, dto.source)
    }

    @Test
    fun recordingState_stopping_preserves_previous() {
        val prev = RecordingState.Recording(1000L, 1, RecordSource.Manual)
        val dto = RecordingState.Stopping(prev, "BYE").toDto()
        assertIs<RecordingStateDto.Stopping>(dto)
        assertEquals("BYE", dto.reason)
        assertEquals(1000L, dto.previous.startMs)
    }

    @Test
    fun recordingState_failed_preserves_reason() {
        val dto = RecordingState.Failed("disk full").toDto()
        assertIs<RecordingStateDto.Failed>(dto)
        assertEquals("disk full", dto.reason)
    }
}

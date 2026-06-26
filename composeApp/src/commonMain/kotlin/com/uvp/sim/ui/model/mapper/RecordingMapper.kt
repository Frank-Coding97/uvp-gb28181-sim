package com.uvp.sim.ui.model.mapper

import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.recording.RecordingState
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.ui.model.RecordingFileDto
import com.uvp.sim.ui.model.RecordingFilterDto
import com.uvp.sim.ui.model.RecordingStateDto
import com.uvp.sim.ui.model.RecordSourceDto

/** PR-A T4.2 实现. type 引用 api.RecordType (B 档,直传); RecordingState sealed 4 variant. */

fun RecordSource.toDto(): RecordSourceDto = RecordSourceDto.valueOf(name)

fun RecordingFile.toDto(): RecordingFileDto = RecordingFileDto(
    id = id,
    startTimeMs = startTimeMs,
    endTimeMs = endTimeMs,
    durationMs = durationMs,
    channelId = channelId,
    filePath = filePath,
    sizeBytes = sizeBytes,
    thumbnailPath = thumbnailPath,
    source = source.toDto(),
    type = type,
    secrecy = secrecy,
)

fun RecordingFilter.toDto(): RecordingFilterDto =
    RecordingFilterDto(startMs, endMs, channelKeyword)

fun RecordingState.toDto(): RecordingStateDto = when (this) {
    RecordingState.Idle -> RecordingStateDto.Idle
    is RecordingState.Recording -> RecordingStateDto.Recording(startMs, segmentIndex, source.toDto())
    is RecordingState.Stopping -> RecordingStateDto.Stopping(
        previous = RecordingStateDto.Recording(previous.startMs, previous.segmentIndex, previous.source.toDto()),
        reason = reason,
    )
    is RecordingState.Failed -> RecordingStateDto.Failed(reason)
}

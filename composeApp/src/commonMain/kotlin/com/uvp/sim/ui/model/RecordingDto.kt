package com.uvp.sim.ui.model

/** 录像来源 DTO. 1:1 映射 com.uvp.sim.recording.RecordSource. */
enum class RecordSourceDto { Manual, PlatformCmd }

/**
 * UI 层 录像文件 DTO. 1:1 映射 com.uvp.sim.recording.RecordingFile.
 * type 字段持有 com.uvp.sim.recording.RecordType (B 档,T1.3 后 typealias).
 */
data class RecordingFileDto(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val channelId: String,
    val filePath: String,
    val sizeBytes: Long,
    val thumbnailPath: String? = null,
    val source: RecordSourceDto = RecordSourceDto.Manual,
    val type: com.uvp.sim.recording.RecordType,
    val secrecy: Int = 0,
)

/** UI 层 录像过滤参数 DTO. 1:1 映射 com.uvp.sim.recording.RecordingFilter. */
data class RecordingFilterDto(
    val startMs: Long,
    val endMs: Long,
    val channelKeyword: String = "",
)

/**
 * UI 层 录像状态机 DTO. 1:1 映射 com.uvp.sim.recording.RecordingState.
 * 4 variant sealed.
 */
sealed class RecordingStateDto {
    data object Idle : RecordingStateDto()
    data class Recording(
        val startMs: Long,
        val segmentIndex: Int,
        val source: RecordSourceDto,
    ) : RecordingStateDto()
    data class Stopping(
        val previous: Recording,
        val reason: String,
    ) : RecordingStateDto()
    data class Failed(val reason: String) : RecordingStateDto()
}

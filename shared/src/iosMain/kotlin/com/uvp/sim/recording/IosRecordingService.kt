package com.uvp.sim.recording

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS 占位 — M2 不在范围。
 *
 * 编译过即可,任何 start 调用返回 NotImplementedError;真要做 iOS 录像走 M3+。
 */
class IosRecordingService : RecordingService {
    override val state: StateFlow<RecordingState> = MutableStateFlow(RecordingState.Idle)
    override val files: StateFlow<List<RecordingFile>> = MutableStateFlow(emptyList())

    override suspend fun start(source: RecordSource, channelId: String): Result<Unit> =
        Result.failure(NotImplementedError("iOS 录像不在 M2 范围"))

    override suspend fun stop(): Result<RecordingFile?> =
        Result.failure(NotImplementedError("iOS 录像不在 M2 范围"))

    override suspend fun load() = Unit
    override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
}

/** iOS Mp4 demux 占位。 */
class IosMp4DemuxSource(private val filePath: String) : Mp4DemuxSource {
    override val firstFramePtsUs: Long = 0L
    override suspend fun open(): Result<Unit> =
        Result.failure(NotImplementedError("iOS Mp4 demux 不在 M2 范围"))
    override fun frames(): Flow<MediaFrame> = emptyFlow()
    override suspend fun close() = Unit
}

package com.uvp.sim.recording

import kotlinx.coroutines.flow.StateFlow

/**
 * 录像引擎对外接口(commonMain)。
 *
 * 真实实现在 androidMain([com.uvp.sim.recording.AndroidRecordingService]) — CameraX
 * VideoCapture + 30 分钟切片;iosMain 占位(M2 不在范围)。SimulatorEngine 通过这个
 * 接口指挥录像,不依赖具体平台。
 *
 * 调用约定:
 *   - [start] 幂等:已在 Recording 时返回 success,不重启
 *   - [stop] 幂等:Idle 时返回 success(file=null)
 *   - [delete] 找不到 id 返回 success(避免双删 race 抛错)
 *   - 任何错误走 [state] 进 Failed,返回值仅表达"指令是否被接受"
 */
interface RecordingService {
    val state: StateFlow<RecordingState>
    val files: StateFlow<List<RecordingFile>>

    /** 启动录像。channelId 通常 = config.device.videoChannelId。 */
    suspend fun start(source: RecordSource, channelId: String): Result<Unit>

    /** 停止录像。返回最新 finalize 的那一段;若不在录像中返回 success(null)。 */
    suspend fun stop(): Result<RecordingFile?>

    /** 启动时调用,从磁盘载入 index.json 到内存。 */
    suspend fun load()

    /** 删除一段录像(文件 + 缩略图 + 索引同步)。 */
    suspend fun delete(id: String): Result<Unit>
}

/**
 * 测试用 / commonMain 默认空实现 — 引擎在没传 RecordingService 时退化为这个,
 * 所有方法报告 Idle / 空列表 / Result.success,不抛。
 *
 * 别的 worktree merge 时若没接录像,引擎会用这个,SIP 主路径不会被影响。
 */
object NoopRecordingService : RecordingService {
    override val state = kotlinx.coroutines.flow.MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val files = kotlinx.coroutines.flow.MutableStateFlow<List<RecordingFile>>(emptyList())

    override suspend fun start(source: RecordSource, channelId: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun stop(): Result<RecordingFile?> = Result.success(null)
    override suspend fun load() = Unit
    override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
}

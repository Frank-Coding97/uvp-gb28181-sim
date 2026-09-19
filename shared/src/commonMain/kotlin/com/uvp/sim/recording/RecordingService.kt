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

    /**
     * 启动录像。channelId 通常 = config.device.videoChannelId。
     *
     * @param streamNumber A.2.3.1.4 `RecordCmd` 携带的 `<StreamNumber>`（**2022 新增**）：
     *   「0-主码流，1-子码流1，2-子码流2，以此类推（可选），缺省 0」。
     *   落到 [RecordingFile.streamNumber]，于是平台按码流筛录像（A.2.4.5 `StreamNumber`）
     *   时设备给得出**与录制时一致**的答案（端到端自洽）。
     *   ⚠️ 口径边界：模拟器只有一路真实码流（手机摄像头），这里只**记账** ——
     *   底层不会真的切换编码档位。与 `VideoParamAttribute` / `BasicParam` 的记账口径一致。
     */
    suspend fun start(source: RecordSource, channelId: String, streamNumber: Int = 0): Result<Unit>

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

    override suspend fun start(source: RecordSource, channelId: String, streamNumber: Int): Result<Unit> =
        Result.success(Unit)

    override suspend fun stop(): Result<RecordingFile?> = Result.success(null)
    override suspend fun load() = Unit
    override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
}

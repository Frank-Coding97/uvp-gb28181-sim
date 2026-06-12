package com.uvp.sim.recording

import kotlinx.serialization.json.Json

/**
 * RecordingIndexFile 的纯函数 API。
 *
 * 设计点:
 *   - encode/decode 使用宽松 JSON,坏数据不 crash 退化为空索引(plan §4.2)
 *   - append/remove 返回新 [RecordingIndexFile],不可变
 *   - queryByTimeRange 用 GB28181 区间重叠规则:start ≤ file.endTimeMs AND end ≥ file.startTimeMs
 *   - 查询结果按 startTimeMs 升序,PLAYBACK 多段拼接需要这个顺序
 */
object RecordingIndex {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun decode(jsonText: String): RecordingIndexFile {
        if (jsonText.isBlank()) return RecordingIndexFile()
        return runCatching { json.decodeFromString(RecordingIndexFile.serializer(), jsonText) }
            .getOrElse { RecordingIndexFile() }
    }

    fun encode(idx: RecordingIndexFile): String =
        json.encodeToString(RecordingIndexFile.serializer(), idx)

    fun append(idx: RecordingIndexFile, file: RecordingFile): RecordingIndexFile =
        idx.copy(files = idx.files + file)

    fun remove(idx: RecordingIndexFile, id: String): RecordingIndexFile {
        val filtered = idx.files.filterNot { it.id == id }
        return if (filtered.size == idx.files.size) idx else idx.copy(files = filtered)
    }

    /**
     * 区间重叠查询。命中条件:`startMs ≤ file.endTimeMs && endMs ≥ file.startTimeMs`(闭区间)。
     * 可选 channelId 和 type 进一步过滤。结果按 startTimeMs 升序。
     */
    fun queryByTimeRange(
        idx: RecordingIndexFile,
        startMs: Long,
        endMs: Long,
        channelId: String? = null,
        type: RecordType? = null
    ): List<RecordingFile> = idx.files
        .asSequence()
        .filter { startMs <= it.endTimeMs && endMs >= it.startTimeMs }
        .filter { channelId == null || it.channelId == channelId }
        .filter { type == null || it.type == type }
        .sortedBy { it.startTimeMs }
        .toList()
}

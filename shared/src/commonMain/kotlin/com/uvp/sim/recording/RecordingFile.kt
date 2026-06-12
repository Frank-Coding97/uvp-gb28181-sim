package com.uvp.sim.recording

import kotlinx.serialization.Serializable

/**
 * 录像来源:用户手动 / 平台 RecordCmd 下发。
 * 仅元数据用,UI 不区分显示。
 */
@Serializable
enum class RecordSource { Manual, PlatformCmd }

/**
 * GB/T 28181 RecordInfo 录像类型。
 * - Time:定时录像(M2 默认)
 * - Alarm:报警录像
 * - Manual_:手动录像(GB token "manual";Kotlin 标识符不能与 [RecordSource.Manual] 重名,加下划线)
 *
 * gb28181Token 用在 RecordInfo Notify XML <Type> 字段。
 */
@Serializable
enum class RecordType(val gb28181Token: String) {
    Time("time"),
    Alarm("alarm"),
    Manual_("manual")
}

/**
 * 一段录像的完整元数据,落在 index.json,也喂给 RecordInfo Notify。
 * 不可变,所有"修改"返回新副本。
 */
@Serializable
data class RecordingFile(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val channelId: String,
    val filePath: String,
    val sizeBytes: Long,
    val thumbnailPath: String? = null,
    val source: RecordSource = RecordSource.Manual,
    val type: RecordType = RecordType.Time,
    val secrecy: Int = 0
)

/**
 * 全量索引文件 schema(磁盘上的 index.json 一一对应)。
 * version 字段留版本演进用;新字段用 default,旧版本读老 JSON 不 crash。
 */
@Serializable
data class RecordingIndexFile(
    val version: Int = 1,
    val files: List<RecordingFile> = emptyList()
)

package com.uvp.sim.gb28181

import com.uvp.sim.recording.RecordType
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * GB/T 28181 §9.4 RecordInfo 查询解析。
 *
 * 平台下发(MESSAGE body):
 * ```xml
 * <Query>
 *   <CmdType>RecordInfo</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>     ← 通道 ID(查哪个通道)
 *   <StartTime>2026-06-01T00:00:00</StartTime>
 *   <EndTime>2026-06-12T23:59:59</EndTime>
 *   <Type>time|alarm|manual|all</Type>  ← 可选
 *   <Secrecy>0</Secrecy>                ← 可选
 *   <!-- M5 batch2 §3.11 高级过滤(自建平台 / EasyGBS 扩展) -->
 *  *   <IndistinctQuery>1</IndistinctQuery> ← 可选,1=模糊查询子设备
 *   <FilePath>...</FilePath>             ← 可选,录像文件路径
 *   <Address>...</Address>               ← 可选,录像地址
 *   <RecorderID>...</RecorderID>         ← 可选,录像器 ID
 *   <!-- 2026-09-19 补：A.2.4.5 的 2022 新增过滤条件 -->
 *   <StreamNumber>0</StreamNumber>       ← 可选,码流编号
 *   <AlarmMethod>5</AlarmMethod>         ← 可选,报警方式(0-全部/1-电话/2-设备/3-短信/4-GPS/5-视频/6-设备故障/7-其他)
 *   <AlarmType>2</AlarmType>             ← 可选,报警类型(取值随 AlarmMethod 变,见标准)
 * </Query>
 * ```
 *
 * 时间是本地时间无偏移,需要传入 [TimeZone] 解码成 epoch ms。
 *
 * **过滤语义**(plan §Q3):
 * - 高级字段(`IndistinctQuery` / `FilePath` / `Address` / `RecorderID`)**仅解析透传**,
 *   不参与 mock 录像命中集过滤
 * - ⭐ 2022 新增的三个(`StreamNumber` / `AlarmMethod` / `AlarmType`)**参与过滤** ——
 *   它们有设备侧可判定的确定语义（本机录像自带码流号与报警类型），
 *   忽略它们等于**静默返回超集**：平台按"视频报警录像"筛，设备把定时录像也一起回了，
 *   而平台无法分辨哪条是超发的。
 *   `IndistinctQuery=1` 留 M6 多通道 / 子目录录像时启用真实生效。
 */
data class RecordInfoQueryRequest(
    val sn: String,
    val channelId: String,
    val startMs: Long,
    val endMs: Long,
    val type: RecordType?,
    val secrecy: Int,
    val indistinctQuery: Int = 0,
    val filePath: String? = null,
    val address: String? = null,
    val recorderId: String? = null,
    /** A.2.4.5 码流编号:0-主码流 / 1-子码流1 / 2-子码流2……。null = 平台没按码流筛。 */
    val streamNumber: Int? = null,
    /** A.2.4.5 报警方式。null = 平台没按报警方式筛。`"0"` 表示"全部"（标准取值域含 0）。 */
    val alarmMethod: String? = null,
    /** A.2.4.5 报警类型。取值域随 [alarmMethod] 变，故按字符串透传，设备侧只做**相等**匹配。 */
    val alarmType: String? = null
)

object RecordInfoQuery {

    fun parse(xml: String, timeZoneId: String): RecordInfoQueryRequest? {
        val sn = ManscdpParser.sn(xml) ?: return null
        val channel = ManscdpParser.deviceId(xml) ?: return null
        val startStr = ManscdpParser.tagValue(xml, "StartTime") ?: return null
        val endStr = ManscdpParser.tagValue(xml, "EndTime") ?: return null
        val tz = runCatching { TimeZone.of(timeZoneId) }.getOrDefault(TimeZone.UTC)
        val startMs = parseLocalIso(startStr, tz) ?: return null
        val endMs = parseLocalIso(endStr, tz) ?: return null
        val typeStr = ManscdpParser.tagValue(xml, "Type")
        val type = when (typeStr?.lowercase()) {
            null, "all" -> null
            "time" -> RecordType.Time
            "alarm" -> RecordType.Alarm
            "manual" -> RecordType.Manual_
            else -> null
        }
        val secrecy = ManscdpParser.tagValue(xml, "Secrecy")?.toIntOrNull() ?: 0
        val indistinctQuery = ManscdpParser.tagValue(xml, "IndistinctQuery")?.toIntOrNull() ?: 0
        val filePath = ManscdpParser.tagValue(xml, "FilePath")?.takeIf { it.isNotBlank() }
        val address = ManscdpParser.tagValue(xml, "Address")?.takeIf { it.isNotBlank() }
        val recorderId = ManscdpParser.tagValue(xml, "RecorderID")?.takeIf { it.isNotBlank() }
        // ⭐ 2026-09-19 补三个 2022 新增过滤条件。⛔ `AlarmMethod`/`AlarmType` 按**字符串**
        //    透传而不归一成 Int：两者取值域互相依赖（方式=2 与方式=5 的 AlarmType 是两套
        //    完全不同的表），归一后设备侧就分不清"方式 5 的类型 2"和"方式 2 的类型 2"了。
        val streamNumber = ManscdpParser.tagValue(xml, "StreamNumber")?.toIntOrNull()
        val alarmMethod = ManscdpParser.tagValue(xml, "AlarmMethod")?.trim()?.takeIf { it.isNotEmpty() }
        val alarmType = ManscdpParser.tagValue(xml, "AlarmType")?.trim()?.takeIf { it.isNotEmpty() }
        return RecordInfoQueryRequest(
            sn = sn,
            channelId = channel,
            startMs = startMs,
            endMs = endMs,
            type = type,
            secrecy = secrecy,
            indistinctQuery = indistinctQuery,
            filePath = filePath,
            address = address,
            recorderId = recorderId,
            streamNumber = streamNumber,
            alarmMethod = alarmMethod,
            alarmType = alarmType
        )
    }

    private fun parseLocalIso(s: String, tz: TimeZone): Long? = runCatching {
        LocalDateTime.parse(s).toInstant(tz).toEpochMilliseconds()
    }.getOrNull()
}

package com.uvp.sim.recording

import kotlinx.serialization.Serializable

/**
 * 录像来源:用户手动 / 平台 RecordCmd 下发 / 设备按本机录像计划自动起录 / 报警触发。
 *
 * [Schedule] 是本仓新增的第三种来源(2026-11-02)—— GB/T 28181-2022 A.2.1.15 的
 * `VideoRecordPlan` 到点由设备自己开录,既不是用户点的,也不是平台 `RecordCmd` 点名的。
 *
 * [Alarm] 是第四种(2026-11-05)—— A.2.1.16 `VideoAlarmRecord` 启用时,报警上报成功后
 * 由设备自己起一段,录到 `RecordTime` 秒自动停。
 *
 * ⚠️ 四种来源在**行为**上还有一层差别,别混:
 *   - [Manual] / [PlatformCmd]:外力起、外力停,设备侧不做任何自主判断
 *   - [Schedule]:设备自己的策略在跑 —— 时段内被外力停掉会被调度器重新拉起(见
 *     `RecordPlanScheduler`),因为"时段内一定在录"才是运维能验证的语义
 *   - [Alarm]:设备自己起、自己停;若起录时**已经在录**(手动/计划/平台),不打断那一段,
 *     改为给它打 [AlarmMark](见 `RecordingService.markAlarm`)
 *
 * ⚠️ 序列化:`index.json` 里存的是枚举名。新增值对**旧文件**解码无影响(旧数据里
 * 只有前几个值);反过来新文件被**旧版本 App** 读会整份索引退化空(`RecordingIndex.decode`
 * 兜住不崩,但列表会空)。属已知降级,不为此保留兼容分支。
 */
@Serializable
enum class RecordSource { Manual, PlatformCmd, Schedule, Alarm }

/**
 * 一段录像里「发生过报警」的标记(A.2.4.5 `Type=alarm` 的设备侧依据)。
 *
 * ⭐ 为什么是标记而不是把 `type` 改成 [RecordType.Alarm]:2026-11-05 定的策略是
 * **录像不停、事后标记** —— 手动/计划录像正在进行时来了报警,不能为了口径好看去把
 * 计划录像切碎(EasyGBS/LiveGBS 上会看到人为的录像缺口)。于是那段录像的主导类型仍是
 * 它原来的类型,而"这段里有报警"由本字段单独表达。
 *
 * ⛔ [alarmMethod] / [alarmType] 存的是**报文原值**(字符串透传,见 `RecordInfoQueryRequest`):
 * 两者的取值域互相依赖(报警方式=2 与 =5 是两套 AlarmType),归一成 Int 会丢信息。
 * 多次报警只保留**第一次**的取值 —— 一段录像里混了两种报警时,平台按第二种筛不到它,
 * 这是刻意保守:宁可漏一条,也不能把 A 类报警的录像当成 B 类回给平台。
 *
 * 默认值齐全:旧 `index.json` 里没有这个键,反序列化必须能兜住。
 */
@Serializable
data class AlarmMark(
    /** 本段录像里**第一次**报警的时刻(ms)。 */
    val firstAlarmAtMs: Long,
    /** 本段录像里累计发生的报警条数。 */
    val count: Int = 1,
    /** 第一次报警的 `AlarmMethod` 原值;报文没带则 null。 */
    val alarmMethod: String? = null,
    /** 第一次报警的 `AlarmType` 原值;报文没带则 null。 */
    val alarmType: String? = null,
)

/**
 * GB/T 28181 RecordInfo 录像类型。
 * - Time:定时录像(M2 默认)
 * - Alarm:报警录像
 * - Manual_:手动录像(GB token "manual";Kotlin 标识符不能与 [RecordSource.Manual] 重名,加下划线)
 *
 * gb28181Token 用在 RecordInfo Notify XML <Type> 字段。
 *
 * PR-A T1.3: 定义已搬到 [com.uvp.sim.api.RecordType] 作为 UI 友好公开 API.
 * 这里保留 typealias 兜底, shared 内部 import com.uvp.sim.recording.RecordType
 * 仍可解析, 无需触发大面积修改.
 */
typealias RecordType = com.uvp.sim.api.RecordType

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
    val secrecy: Int = 0,
    /**
     * A.2.3.1.4 `RecordCmd` 携带的码流号（0-主码流 / 1-子码流1 / 2-子码流2，以此类推）。
     *
     * ⚠️ **口径边界（刻意为之）**：模拟器只有**一路真实码流**（手机摄像头），所以这里只
     * **记账** —— 平台按码流筛录像时（A.2.4.5 `StreamNumber`）设备能给出确定答案，
     * 但底层并没有真的切换编码档位。与 `VideoParamAttribute` / `BasicParam` 的记账口径一致。
     *
     * 带默认值 `0`：旧 `index.json` 里没有这个键，反序列化必须能兜住（本仓曾因
     * 新增字段没给默认值而整份配置丢失）。
     */
    val streamNumber: Int = 0,
    /**
     * 本段录像期间发生过的报警(A.2.1.16 报警录像)。`null` = 这段没有报警。
     *
     * ⛔ 与 [type] 是两个独立维度:[type] 说"这段是**因为什么**录的",本字段说
     * "这段**里面**有没有报警"。把两者合并会让「报警时正在录计划录像」这件事无解 ——
     * 要么谎称它是报警录像(计划录像的语义被吃掉),要么丢掉报警信息。
     */
    val alarm: AlarmMark? = null
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

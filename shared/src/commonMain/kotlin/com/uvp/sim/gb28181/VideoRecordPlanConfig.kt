package com.uvp.sim.gb28181

import kotlinx.serialization.Serializable

/** 两位补零（`8` → `"08"`）。`commonMain` 里没有 `String.format`，只能自己拼。 */
private fun hh(value: Int): String = value.toString().padStart(2, '0')

/**
 * 录像计划里的一个时间段（`TimeSegment`）。
 *
 * 六个字段都是**必选 integer**：起止的时 / 分 / 秒，无日期概念（"每天的这个区间"）。
 * 取值范围：时 0~23 / 分 0~59 / 秒 0~59。
 *
 * ⚠️ 标准**没有**规定 `Stop < Start` 时算"跨零点"还是算非法，本仓**不解释、如实记账**
 * （渲染时原样回传）。要不要把它当跨零点视频段是**平台侧的业务解释**，
 * 设备侧替它决定会让回读值与下发值不一致。
 *
 * ⚠️ 本类型会进设备状态存档，所以**所有字段都给默认值**（哪怕 wire 上必选）——
 * 理由见 [PictureMaskRegion] 的注释：没默认值的字段一加，旧存档就整份解不出来。
 * 校验只认 [VideoRecordPlanConfig.parse]。
 */
@Serializable
data class RecordTimeSegment(
    val startHour: Int = 0,
    val startMin: Int = 0,
    val startSec: Int = 0,
    val stopHour: Int = 0,
    val stopMin: Int = 0,
    val stopSec: Int = 0,
) {
    /**
     * 人读串（UI / 日志），形如 `08:00:00-12:00:00`。
     *
     * ⛔ 用 `padStart` 而不是 `String.format` —— 后者是 **JVM 专属**，在 `commonMain` 里
     * 只有 iOS 那条编译门禁抓得到 `Unresolved reference 'format'`（Android 目标编得过）。
     */
    val displayLabel: String
        get() = "${hh(startHour)}:${hh(startMin)}:${hh(startSec)}-${hh(stopHour)}:${hh(stopMin)}:${hh(stopSec)}"
}

/**
 * 周几的录像计划（`RecordSchedule`）。
 *
 * [weekDayNum] 取值 **1~7 表示周一到周日**（标准原文：「一周几（必选）取值1～7，
 * 表示周一到周日，如当天无录像计划可缺少」）。
 *
 * ⭐ "如当天无录像计划可缺少" ⇒ **没有计划的那天就整条不发**，不发一条 `TimeSegment=0`
 * 的空记录。所以 [segments] 允许为空列表（渲染时 `TimeSegmentSumNum=0` 且不发任何
 * `TimeSegment`）—— 与"这条 `RecordSchedule` 不存在"在语义上等价，但设备侧只回它真有的那些天。
 */
@Serializable
data class RecordSchedule(
    val weekDayNum: Int = 0,
    /** 该天的时段，最多 8 个（标准 `TimeSegment maxOccurs="8"`）。 */
    val segments: List<RecordTimeSegment> = emptyList(),
)

/**
 * GB/T 28181-2022 **A.2.1.15 录像计划配置类型**（`videoRecordPlanCfgType`）的当前取值。
 *
 * 结构（这一族里字段数最多）：
 * ```xml
 * <VideoRecordPlan>
 *   <RecordEnable>1</RecordEnable>              <!-- 是否启用时间计划录像，0-否/1-是（必选） -->
 *   <RecordScheduleSumNum>2</RecordScheduleSumNum>  <!-- 每周计划总天数（必选） -->
 *   <RecordSchedule>                            <!-- 最多 7 条，对应周一~周日 -->
 *     <WeekDayNum>1</WeekDayNum>                <!-- 1~7 = 周一~周日（必选） -->
 *     <TimeSegmentSumNum>1</TimeSegmentSumNum>  <!-- 当天时段总数（必选） -->
 *     <TimeSegment>                             <!-- 每天最多 8 段（minOccurs=1） -->
 *       <StartHour>8</StartHour><StartMin>0</StartMin><StartSec>0</StartSec>
 *       <StopHour>12</StopHour><StopMin>0</StopMin><StopSec>0</StopSec>
 *     </TimeSegment>
 *   </RecordSchedule>
 *   <StreamNumber>0</StreamNumber>              <!-- 码流编号：0-主码流（必选） -->
 * </VideoRecordPlan>
 * ```
 *
 * ⭐ 两个计数（`RecordScheduleSumNum` / `TimeSegmentSumNum`）**不独立存** ——
 * 分别恒等于 `schedules.size` 与 `schedule.segments.size`，渲染时现算。
 * 存成独立字段必然出现"计数说 2 天、实际 1 天"的自相矛盾状态（同 [PictureMaskState.sumNum]）。
 *
 * ⚠️ 本仓前端这个组只支持**单时段**、也没有任何计数字段 —— 那是照海康 ISP 口径编的，
 * 接后端之前必须先按本条对齐，否则一天里第 2 段之后的计划会被静默丢掉、**且看不出来**。
 */
@Serializable
data class VideoRecordPlanState(
    /** `RecordEnable`，0/1。默认 0 = 不启用计划录像。 */
    val recordEnable: Int = 0,
    /** 计划，按 [RecordSchedule.weekDayNum] 升序。最多 7 条。 */
    val schedules: List<RecordSchedule> = emptyList(),
    /** `StreamNumber`：0-主码流 / 1-子码流1…。默认主码流。 */
    val streamNumber: Int = 0,
) {
    /** 线格式用：每周计划总天数（= 实际条数）。 */
    val scheduleSumNum: Int get() = schedules.size
}

/**
 * `VideoRecordPlan` 的取值表、出厂默认与线格式。
 *
 * ⛔ 与 [PictureMaskConfig] 同一口径：**任何结构违规一律整块拒绝**（不动状态 + warn）。
 * 半份录像计划比没有计划更危险 —— 运维会以为"录像是按计划开的"，实际缺了一天。
 */
object VideoRecordPlanConfig {

    private const val OFF = 0
    private const val ON = 1

    /** 标准上限：`RecordSchedule maxOccurs="7"`、`TimeSegment maxOccurs="8"`。 */
    const val MAX_SCHEDULES = 7
    const val MAX_SEGMENTS_PER_DAY = 8

    /** `WeekDayNum` 值域：1~7 = 周一到周日。 */
    const val MIN_WEEK_DAY = 1
    const val MAX_WEEK_DAY = 7

    /** 出厂默认 = 不启用计划录像、无计划、主码流（设备上电后的物理事实）。 */
    fun defaultFor(@Suppress("UNUSED_PARAMETER") config: com.uvp.sim.config.SimConfig) =
        VideoRecordPlanState(recordEnable = OFF, schedules = emptyList(), streamNumber = 0)

    /** 解析下发报文。 */
    fun parse(xml: String): ConfigParse<VideoRecordPlanState> {
        val body = configBlockBody(xml, DeviceConfigBlock.VideoRecordPlan.configType)
            ?: return absentOrEmptyBlock(xml, DeviceConfigBlock.VideoRecordPlan.configType)

        val enable = childInt(body, "RecordEnable")
            ?: return ConfigParse.Rejected("缺必选字段 RecordEnable")
        if (enable != OFF && enable != ON) {
            return ConfigParse.Rejected("RecordEnable=$enable 非法（只能 $OFF/$ON）")
        }
        // 计数字段同样只检查"在不在"：真值取实到条数，但结构不完整要早暴露。
        childInt(body, "RecordScheduleSumNum")
            ?: return ConfigParse.Rejected("缺必选字段 RecordScheduleSumNum")

        val scheduleBodies = splitTopLevel(body, "RecordSchedule")
        if (scheduleBodies.size > MAX_SCHEDULES) {
            return ConfigParse.Rejected("计划天数 ${scheduleBodies.size} 超过标准上限 $MAX_SCHEDULES")
        }

        val schedules = mutableListOf<RecordSchedule>()
        for ((dayIndex, scheduleBody) in scheduleBodies.withIndex()) {
            val weekDay = childInt(scheduleBody, "WeekDayNum")
                ?: return ConfigParse.Rejected("第 ${dayIndex + 1} 条 RecordSchedule 缺必选字段 WeekDayNum")
            if (weekDay !in MIN_WEEK_DAY..MAX_WEEK_DAY) {
                return ConfigParse.Rejected(
                    "第 ${dayIndex + 1} 条 RecordSchedule 的 WeekDayNum=$weekDay 越界" +
                        "（合法 $MIN_WEEK_DAY~$MAX_WEEK_DAY，1=周一）"
                )
            }
            if (schedules.any { it.weekDayNum == weekDay }) {
                return ConfigParse.Rejected("周 $weekDay 的录像计划重复出现")
            }
            childInt(scheduleBody, "TimeSegmentSumNum")
                ?: return ConfigParse.Rejected("周 $weekDay 缺必选字段 TimeSegmentSumNum")

            val segmentBodies = splitTopLevel(scheduleBody, "TimeSegment")
            if (segmentBodies.size > MAX_SEGMENTS_PER_DAY) {
                return ConfigParse.Rejected(
                    "周 $weekDay 的时段数 ${segmentBodies.size} 超过标准上限 $MAX_SEGMENTS_PER_DAY"
                )
            }
            val segments = mutableListOf<RecordTimeSegment>()
            for ((segIndex, segBody) in segmentBodies.withIndex()) {
                val fields = listOf(
                    "StartHour" to 0..23, "StartMin" to 0..59, "StartSec" to 0..59,
                    "StopHour" to 0..23, "StopMin" to 0..59, "StopSec" to 0..59,
                )
                val values = mutableListOf<Int>()
                for ((tag, range) in fields) {
                    val v = childInt(segBody, tag) ?: return ConfigParse.Rejected(
                        "周 $weekDay 第 ${segIndex + 1} 段缺必选字段 $tag"
                    )
                    if (v !in range) {
                        return ConfigParse.Rejected(
                            "周 $weekDay 第 ${segIndex + 1} 段的 $tag=$v 越界（合法 ${range.first}~${range.last}）"
                        )
                    }
                    values += v
                }
                segments += RecordTimeSegment(
                    startHour = values[0], startMin = values[1], startSec = values[2],
                    stopHour = values[3], stopMin = values[4], stopSec = values[5],
                )
            }
            schedules += RecordSchedule(weekDayNum = weekDay, segments = segments)
        }

        val streamNumber = childInt(body, "StreamNumber")
            ?: return ConfigParse.Rejected("缺必选字段 StreamNumber")
        if (streamNumber < 0) {
            return ConfigParse.Rejected("StreamNumber=$streamNumber 非法（0 起，主码流=0）")
        }

        return ConfigParse.Accepted(
            VideoRecordPlanState(
                recordEnable = enable,
                schedules = schedules.sortedBy { it.weekDayNum },
                streamNumber = streamNumber,
            )
        )
    }

    /**
     * 切出指定标签的**顶层**兄弟元素体（用于 `RecordSchedule` / `TimeSegment` 这类嵌套列表）。
     *
     * 两件事必须同时成立，缺一条就会**静默解析成空列表**（现象：平台配了录像计划、
     * 设备回读是空的，两侧都不报错）：
     *
     *  1. **递归处理嵌套**：`TimeSegment` 嵌在 `RecordSchedule` 里，所以不能用
     *     「找第一个开始标签 + 找第一个结束标签」的写法 —— 那会把外层整体吃进去。
     *     这里按**同名标签配平**扫描，只收集深度为 1 的那些。
     *  2. **标签名必须按边界匹配**（走 [openTagEnd]）：本类型的语法里
     *     `RecordSchedule` 是 `RecordScheduleSumNum` 的**前缀**，
     *     用 `indexOf("<RecordSchedule")` 会先命中那个计数元素，
     *     于是深度从 1 被加成 2、再也回不到 0 —— 整份计划被判成"没有计划"。
     */
    private fun splitTopLevel(xml: String, tag: String): List<String> {
        val close = "</$tag>"
        val collected = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val startEnd = openTagEnd(xml, tag, cursor) ?: break
            if (xml[startEnd - 1] == '/') {
                cursor = startEnd + 1
                continue
            }
            var depth = 1
            var scan = startEnd + 1
            while (depth > 0) {
                val nextOpen = openTagEnd(xml, tag, scan)
                val nextClose = xml.indexOf(close, scan)
                if (nextClose < 0) return collected // 结构坏了：交给字段校验兜住
                if (nextOpen != null && nextOpen < nextClose) {
                    depth++
                    scan = nextOpen + 1
                } else {
                    depth--
                    if (depth == 0) {
                        collected += xml.substring(startEnd + 1, nextClose)
                        cursor = nextClose + close.length
                    } else {
                        scan = nextClose + close.length
                    }
                }
            }
        }
        return collected
    }

    /**
     * 回读应答块。
     *
     * ⛔ 元素顺序必须与 XSD 一致：`RecordEnable` → `RecordScheduleSumNum` →
     * `RecordSchedule*`（**`StreamNumber` 在最后**，不在 `RecordSchedule` 里）。
     * 顺序写错不会报错，但「照标准逐行核对」这件事就失效了。
     * ⛔ 两个计数都由实际条数现算。
     */
    fun render(state: VideoRecordPlanState): String = buildString {
        append("<VideoRecordPlan>\n")
        append("<RecordEnable>").append(state.recordEnable).append("</RecordEnable>\n")
        append("<RecordScheduleSumNum>").append(state.scheduleSumNum).append("</RecordScheduleSumNum>\n")
        for (schedule in state.schedules) {
            append("<RecordSchedule>\n")
            append("<WeekDayNum>").append(schedule.weekDayNum).append("</WeekDayNum>\n")
            append("<TimeSegmentSumNum>").append(schedule.segments.size).append("</TimeSegmentSumNum>\n")
            for (segment in schedule.segments) {
                append("<TimeSegment>")
                append("<StartHour>").append(segment.startHour).append("</StartHour>")
                append("<StartMin>").append(segment.startMin).append("</StartMin>")
                append("<StartSec>").append(segment.startSec).append("</StartSec>")
                append("<StopHour>").append(segment.stopHour).append("</StopHour>")
                append("<StopMin>").append(segment.stopMin).append("</StopMin>")
                append("<StopSec>").append(segment.stopSec).append("</StopSec>")
                append("</TimeSegment>\n")
            }
            append("</RecordSchedule>\n")
        }
        append("<StreamNumber>").append(state.streamNumber).append("</StreamNumber>\n")
        append("</VideoRecordPlan>\n")
    }
}

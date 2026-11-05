package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig
import kotlin.random.Random

/**
 * 预置报警模板(spec G3)。受控随机的素材库 —— 每条都是合理组合,
 * 演示时不会造出"视频丢失+GPS方式"的鬼组合。
 *
 * 模板只定语义字段(类型/等级/方式/描述);deviceId / 经纬度在
 * [toPayload] 时从 config 填充(alarmChannelId + mockPosition)。
 */
data class AlarmTemplate(
    val type: AlarmType,
    val priority: AlarmPriority,
    val method: AlarmMethod,
    val description: String,
    /**
     * 设备侧门控类别 —— 决定这条模板受不受 A.2.1.18 的上报开关约束。
     * `null` = 通用报警（人工触发 / 器件故障…），开关管不着。
     */
    val reportKind: AlarmReportKind? = null,
    /**
     * 写进 `<AlarmTypeParam>` 的扩展值。
     *
     * ⚠️ 国标 `AlarmType` 只到 5（其他），移动侦测/区域入侵没有专用编码，平台侧只能靠
     * 这个可选字段区分。各家约定不一，这里用最直白的 `motion` / `field`；
     * **它只影响平台怎么显示，不影响设备发不发**（门控看 [reportKind]）。
     */
    val typeParam: String? = null,
) {
    fun toPayload(config: SimConfig): AlarmPayload = AlarmPayload(
        deviceId = config.device.alarmChannelId,
        priority = priority,
        method = method,
        type = type,
        typeParam = typeParam,
        reportKind = reportKind,
        description = description,
        longitude = config.mockPosition.longitude,
        latitude = config.mockPosition.latitude
    )
}

object AlarmTemplates {

    /** 内置模板（spec G3 表，2026-11 补区域入侵）。 */
    val builtin: List<AlarmTemplate> = listOf(
        AlarmTemplate(AlarmType.VideoLost, AlarmPriority.General, AlarmMethod.Video, "通道视频信号丢失"),
        AlarmTemplate(AlarmType.DeviceTamper, AlarmPriority.EmergencyL2, AlarmMethod.Device, "设备外壳被打开"),
        AlarmTemplate(AlarmType.StorageFull, AlarmPriority.General, AlarmMethod.Device, "存储空间不足"),
        AlarmTemplate(AlarmType.DeviceFault, AlarmPriority.EmergencyL3, AlarmMethod.DeviceFault, "设备硬件异常"),
        AlarmTemplate(AlarmType.Other, AlarmPriority.General, AlarmMethod.Manual, "人工触发报警"),
        AlarmTemplate(
            AlarmType.Other, AlarmPriority.EmergencyL1, AlarmMethod.Video, "移动侦测触发",
            reportKind = AlarmReportKind.MotionDetection, typeParam = "motion"
        ),
        AlarmTemplate(
            AlarmType.Other, AlarmPriority.EmergencyL1, AlarmMethod.Video, "区域入侵触发",
            reportKind = AlarmReportKind.FieldDetection, typeParam = "field"
        )
    )

    /**
     * 受控随机：从 [builtin] 抽一个。seed 可注入 → 可测确定性。
     *
     * [allowed] 用来把**当前上报开关关掉的类别**排除出候选池 —— 否则「随机发一条」会
     * 定期抽到一条根本不发出去的模板，界面上表现为"点了没反应"。
     * 池子空时退回全量（不可能是空：通用模板不受开关约束），只是防御写错。
     */
    fun random(
        rnd: Random = Random,
        allowed: (AlarmReportKind?) -> Boolean = { true },
    ): AlarmTemplate {
        val pool = builtin.filter { allowed(it.reportKind) }.ifEmpty { builtin }
        return pool[rnd.nextInt(pool.size)]
    }
}

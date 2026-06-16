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
    val description: String
) {
    fun toPayload(config: SimConfig): AlarmPayload = AlarmPayload(
        deviceId = config.device.alarmChannelId,
        priority = priority,
        method = method,
        type = type,
        description = description,
        longitude = config.mockPosition.longitude,
        latitude = config.mockPosition.latitude
    )
}

object AlarmTemplates {

    /** 内置 6 条(spec G3 表)。 */
    val builtin: List<AlarmTemplate> = listOf(
        AlarmTemplate(AlarmType.VideoLost, AlarmPriority.General, AlarmMethod.Video, "通道视频信号丢失"),
        AlarmTemplate(AlarmType.DeviceTamper, AlarmPriority.EmergencyL2, AlarmMethod.Device, "设备外壳被打开"),
        AlarmTemplate(AlarmType.StorageFull, AlarmPriority.General, AlarmMethod.Device, "存储空间不足"),
        AlarmTemplate(AlarmType.DeviceFault, AlarmPriority.EmergencyL3, AlarmMethod.DeviceFault, "设备硬件异常"),
        AlarmTemplate(AlarmType.Other, AlarmPriority.General, AlarmMethod.Manual, "人工触发报警"),
        AlarmTemplate(AlarmType.Other, AlarmPriority.EmergencyL1, AlarmMethod.Video, "移动侦测触发")
    )

    /** 受控随机:从 [builtin] 抽一个。seed 可注入 → 可测确定性。 */
    fun random(rnd: Random = Random): AlarmTemplate = builtin[rnd.nextInt(builtin.size)]
}

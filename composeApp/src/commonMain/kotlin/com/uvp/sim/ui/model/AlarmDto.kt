package com.uvp.sim.ui.model

/** UI 层 报警类型 DTO. 1:1 映射 com.uvp.sim.gb28181.AlarmType (含 code/label). */
enum class AlarmTypeDto(val code: Int, val label: String) {
    VideoLost(1, "视频丢失"),
    DeviceTamper(2, "设备防拆"),
    StorageFull(3, "存储满"),
    DeviceFault(4, "设备故障"),
    Other(5, "其他"),
}

/** UI 层 报警优先级 DTO. 1:1 映射 com.uvp.sim.gb28181.AlarmPriority. */
enum class AlarmPriorityDto(val code: Int, val label: String) {
    EmergencyL1(1, "紧急一级"),
    EmergencyL2(2, "紧急二级"),
    EmergencyL3(3, "紧急三级"),
    General(4, "一般报警"),
}

/** UI 层 报警方式 DTO. 1:1 映射 com.uvp.sim.gb28181.AlarmMethod. */
enum class AlarmMethodDto(val code: Int, val label: String) {
    Phone(1, "电话"),
    Device(2, "设备"),
    Sms(3, "短信"),
    Gps(4, "GPS"),
    Video(5, "视频"),
    DeviceFault(6, "器件"),
    Manual(7, "人工"),
}

/** UI 层 报警单 DTO. 1:1 映射 com.uvp.sim.gb28181.AlarmPayload. */
data class AlarmPayloadDto(
    val deviceId: String,
    val priority: AlarmPriorityDto = AlarmPriorityDto.General,
    val method: AlarmMethodDto = AlarmMethodDto.Device,
    val type: AlarmTypeDto = AlarmTypeDto.Other,
    val typeParam: String? = null,
    val timeMs: Long = 0L,
    val description: String = "",
    val longitude: Double? = null,
    val latitude: Double? = null,
)

/** UI 层 已发报警历史记录 DTO. 1:1 映射 com.uvp.sim.domain.AlarmRecord. */
data class AlarmRecordDto(
    val payload: AlarmPayloadDto,
    val firedAtMs: Long,
    val notifiedSubscribers: Int,
)

package com.uvp.sim.gb28181

import kotlinx.serialization.Serializable

/**
 * GB/T 28181-2022 报警事件编码 (§9.5.1 + §A.2.5)。
 *
 * 三个独立维度,UI 选择器从 [entries] 渲染 label,XML body 输出 [code]。
 * [byCode] 给从平台报文反查枚举用(missing 返回 null,调用方兜底)。
 */
@Serializable
enum class AlarmPriority(val code: Int, val label: String) {
    EmergencyL1(1, "紧急一级"),
    EmergencyL2(2, "紧急二级"),
    EmergencyL3(3, "紧急三级"),
    General(4, "一般报警");

    companion object {
        fun byCode(code: Int): AlarmPriority? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
enum class AlarmMethod(val code: Int, val label: String) {
    Phone(1, "电话"),
    Device(2, "设备"),
    Sms(3, "短信"),
    Gps(4, "GPS"),
    Video(5, "视频"),
    DeviceFault(6, "器件"),
    Manual(7, "人工");

    companion object {
        fun byCode(code: Int): AlarmMethod? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
enum class AlarmType(val code: Int, val label: String) {
    VideoLost(1, "视频丢失"),
    DeviceTamper(2, "设备防拆"),
    StorageFull(3, "存储满"),
    DeviceFault(4, "设备故障"),
    Other(5, "其他");

    companion object {
        fun byCode(code: Int): AlarmType? = entries.firstOrNull { it.code == code }
    }
}

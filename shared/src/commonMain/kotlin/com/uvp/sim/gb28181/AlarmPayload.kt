package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig
import kotlinx.serialization.Serializable

/**
 * GB/T 28181-2022 §9.5.1 报警通知载荷。纯数据 + 默认值,不带行为。
 *
 * [timeMs] = 0 表示"由 builder 序列化时取 now";非 0 则按该 epoch ms 格式化。
 * 经纬度可空,null 时 builder 输出空 element(WVP 容忍)。
 */
@Serializable
data class AlarmPayload(
    val deviceId: String,
    val priority: AlarmPriority = AlarmPriority.General,
    val method: AlarmMethod = AlarmMethod.Device,
    val type: AlarmType = AlarmType.Other,
    val typeParam: String? = null,
    /**
     * 设备侧门控标签（A.2.1.18）：这条报警算不算"移动侦测 / 区域入侵"事件。
     *
     * ⛔ **不进 XML，也不进任何持久化格式**（`@Transient`）—— 它是本机运行时策略，
     * 只在设备内部决定要不要被两个上报开关拦下（见 [AlarmReportConfig.allows]）。
     * `null` = 与那两个开关无关（人工触发、器件故障…）。
     *
     * ⚠️ 为什么不复用 [typeParam]：那是**发给平台**的展示文本，可被清空/改写；
     * 拿它反查门控等于让对端决定本机发不发报警。
     */
    @kotlinx.serialization.Transient
    val reportKind: AlarmReportKind? = null,
    val timeMs: Long = 0L,
    val description: String = "",
    val longitude: Double? = null,
    val latitude: Double? = null
) {
    companion object {
        /**
         * 主屏一键报警的默认载荷(spec Q1 拍板:type=Other / priority=General /
         * method=Device,描述直白点明手动触发)。
         */
        fun quickDefault(config: SimConfig): AlarmPayload =
            AlarmPayload(
                deviceId = config.device.alarmChannelId,
                description = "设备主动报警(手动触发)"
            )
    }
}

package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.SimConfig

/**
 * GB/T 28181 §9.3.3 AlarmStatus 应答的运行期快照。
 * 由 SimulatorEngine 在响应时从 DeviceControlState 抓取。
 */
data class AlarmStatusSnapshot(
    /** 是否处于报警中(对应 4.6 AlarmCmd 的实际状态) */
    val alarming: Boolean,
    /** 报警通道 ID(GB-2022 Item 子节点要用) */
    val alarmChannelId: String
)

/**
 * GB/T 28181 §9.3.3 AlarmStatusQuery 应答构造。
 *
 * 平台下发 MESSAGE body:
 * ```xml
 * <Query>
 *   <CmdType>AlarmStatus</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 * </Query>
 * ```
 *
 * 设备回 MESSAGE body(GB-2022):
 * ```xml
 * <Response>
 *   <CmdType>AlarmStatus</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>{device.deviceId}</DeviceID>
 *   <Result>OK</Result>
 *   <Num>1</Num>
 *   <Item>
 *     <DeviceID>{alarmChannelId}</DeviceID>
 *     <DutyStatus>ALARM|OFFDUTY</DutyStatus>
 *   </Item>
 * </Response>
 * ```
 *
 * GB-2016 变体:
 * ```xml
 * <Response>
 *   <CmdType>AlarmStatus</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <Result>OK</Result>
 *   <NotNumber>0|1</NotNumber>      ← 国标原文用词,未复位报警数
 * </Response>
 * ```
 *
 * 跟 DeviceStatusResponse 同源思路:沿用 Num+Item 嵌套,平台兼容性已被 DeviceStatus 真机验证过。
 */
object AlarmStatusResponse {

    fun build(
        config: SimConfig,
        sn: String,
        snapshot: AlarmStatusSnapshot
    ): String {
        val device = config.device
        val body = if (config.gbVersion == GbVersion.V2022) {
            val dutyStatus = if (snapshot.alarming) "ALARM" else "OFFDUTY"
            """<Num>1</Num>
<Item>
<DeviceID>${snapshot.alarmChannelId}</DeviceID>
<DutyStatus>$dutyStatus</DutyStatus>
</Item>"""
        } else {
            val n = if (snapshot.alarming) "1" else "0"
            "<NotNumber>$n</NotNumber>"
        }
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>AlarmStatus</CmdType>
<SN>$sn</SN>
<DeviceID>${device.deviceId}</DeviceID>
<Result>OK</Result>
$body
</Response>
""".replace("\n", "\r\n")
    }
}

package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig

/**
 * GB/T 28181 §9.3.3 DeviceStatus 应答的运行期快照。
 * 由 SimulatorEngine 在响应时 snapshot 一份当前状态喂给 builder。
 */
data class DeviceStatusSnapshot(
    /** 设备是否在线 — 注册成功即 true */
    val online: Boolean,
    /** 设备本地时间(ISO8601 本地时间无偏移),如 "2026-06-13T18:00:00" */
    val deviceTime: String,
    /** 是否录像中(对应 4.4 RecordCmd 的实际状态) */
    val recording: Boolean,
    /** 是否处于报警中(对应 4.6 AlarmCmd 的实际状态) */
    val alarming: Boolean,
    /** 是否布防中(对应 4.5 GuardCmd 的实际状态) */
    val guarded: Boolean = false,
    /** 编码错误率 — 国标定义为 0.00–1.00 之间的字符串,模拟器固定 0.00 */
    val encodeErrorRate: String = "0.00"
)

/**
 * GB/T 28181 §9.3.3 DeviceStatus 应答构造。
 *
 * 平台下发 MESSAGE body:
 * ```xml
 * <Query>
 *   <CmdType>DeviceStatus</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 * </Query>
 * ```
 *
 * 设备回 MESSAGE body:
 * ```xml
 * <Response>
 *   <CmdType>DeviceStatus</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <Result>OK</Result>
 *   <Online>ONLINE</Online>
 *   <Status>OK</Status>
 *   <DeviceTime>2026-06-13T18:00:00</DeviceTime>
 *   <Encode>ON</Encode>
 *   <Record>ON|OFF</Record>
 *   <Alarmstatus>                                 ← GB-2022 新增分组,GB-2016 是 <AlarmStatus> 数字
 *     <Num>1</Num>
 *     <Item><DeviceID>..</DeviceID><DutyStatus>OFFDUTY</DutyStatus></Item>
 *   </Alarmstatus>
 * </Response>
 * ```
 *
 * GB-2016/GB-2022 差异:
 *   - 2016: <AlarmStatus>0|1</AlarmStatus> (数字)
 *   - 2022: <Alarmstatus> 嵌套结构,通道级状态明细
 */
object DeviceStatusResponse {

    fun build(
        config: SimConfig,
        sn: String,
        snapshot: DeviceStatusSnapshot
    ): String {
        val device = config.device
        val onlineToken = if (snapshot.online) "ONLINE" else "OFFLINE"
        val recordToken = if (snapshot.recording) "ON" else "OFF"
        val alarmBlock = if (config.gbVersion == com.uvp.sim.config.GbVersion.V2022) {
            // GB-2022:嵌套结构,设备 1 个报警通道
            val dutyStatus = if (snapshot.alarming) "ALARM" else "OFFDUTY"
            """<Alarmstatus>
<Num>1</Num>
<Item>
<DeviceID>${device.alarmChannelId}</DeviceID>
<DutyStatus>$dutyStatus</DutyStatus>
</Item>
</Alarmstatus>"""
        } else {
            // GB-2016:扁平 0/1
            val v = if (snapshot.alarming) "1" else "0"
            "<AlarmStatus>$v</AlarmStatus>"
        }
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>DeviceStatus</CmdType>
<SN>$sn</SN>
<DeviceID>${device.deviceId}</DeviceID>
<Result>OK</Result>
<Online>$onlineToken</Online>
<Status>OK</Status>
<DeviceTime>${snapshot.deviceTime}</DeviceTime>
<Encode>ON</Encode>
<Record>$recordToken</Record>
$alarmBlock
</Response>
""".replace("\n", "\r\n")
    }
}

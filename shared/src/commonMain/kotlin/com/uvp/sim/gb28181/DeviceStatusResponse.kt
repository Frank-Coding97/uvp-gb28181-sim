package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
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
 *   <Alarmstatus Num="1">                    ← 两版**同构**，只有属性名大小写不同
 *     <Item><DeviceID>..</DeviceID><DutyStatus>OFFDUTY</DutyStatus></Item>
 *   </Alarmstatus>
 * </Response>
 * ```
 *
 * ⛔ **2026-09-19 两处按标准改正**（原先 2016 分支是编的）:
 *
 *  1. **2016 也是嵌套 `Alarmstatus`，不是扁平数字。**
 *     原先 2016 分支发 `<AlarmStatus>0|1</AlarmStatus>`，而 2016 附录 A.2.6 g) 与
 *     §9.5.3.3.2 正文（标准页 28）**都**写的是
 *     `Alarmstatus`（小写 s）+ `Item{DeviceID, DutyStatus}`，
 *     正文原话「报警设备状态列表**应包括**报警设备或区域或系统编码（DeviceID）、
 *     报警设备状态（DutyStatus）」。⇒ 2016 有效版本下设备发的是**非 schema 元素**，
 *     严格校验的对端会判整条报文非法。
 *  2. **`Num` 是 `Alarmstatus` 的*属性*，不是子元素。**
 *     原先 2022 分支发 `<Num>1</Num>` 子元素 —— 同一类坑本项目在 `RegionList` /
 *     `VideoParamAttribute` 上已踩过两次。
 *
 * 两版的差异**只有属性名**：2016 `num`（小写，A.2.6 g)）/ 2022 `Num`（大写，A.2.6.6）。
 * `AlarmStatus`（大写 S）这个名字在 2016 附录 A 与 2022 全书**都是 0 命中**。
 */
object DeviceStatusResponse {

    fun build(
        config: SimConfig,
        sn: String,
        snapshot: DeviceStatusSnapshot,
        gbVersion: GbVersion = config.gbVersion,
        requestedDeviceId: String = config.device.deviceId,
    ): String {
        val device = config.device
        val onlineToken = if (snapshot.online) "ONLINE" else "OFFLINE"
        val recordToken = if (snapshot.recording) "ON" else "OFF"
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>DeviceStatus</CmdType>
<SN>$sn</SN>
<DeviceID>$requestedDeviceId</DeviceID>
<Result>OK</Result>
<Online>$onlineToken</Online>
<Status>OK</Status>
<DeviceTime>${snapshot.deviceTime}</DeviceTime>
<Encode>ON</Encode>
<Record>$recordToken</Record>
${alarmStatusBlock(device.alarmChannelId, snapshot.alarming, gbVersion)}
</Response>
""".replace("\n", "\r\n")
    }

    /**
     * A.2.6.6 / 2016 A.2.6 g) 报警设备状态列表。
     *
     * 本机只有**一个**报警通道，所以 `Item` 恒一条、`num`/`Num` 恒 `1`
     * （标准里它是"列表项个数"）。状态取真实位：正在报警 `ALARM`，否则 `OFFDUTY`
     * （从不发 `ONDUTY` —— 手机模拟器没有"在岗"这个业务语义，编一个会让平台显示
     * 一个设备自己都不知道的状态）。
     */
    private fun alarmStatusBlock(
        alarmChannelId: String,
        alarming: Boolean,
        gbVersion: GbVersion,
    ): String {
        val dutyStatus = if (alarming) "ALARM" else "OFFDUTY"
        // 属性名大小写是**唯一**的版本差异（2016 num / 2022 Num）。
        val numAttr = if (gbVersion >= GbVersion.V2022) "Num" else "num"
        return """<Alarmstatus $numAttr="1">
<Item>
<DeviceID>$alarmChannelId</DeviceID>
<DutyStatus>$dutyStatus</DutyStatus>
</Item>
</Alarmstatus>"""
    }
}

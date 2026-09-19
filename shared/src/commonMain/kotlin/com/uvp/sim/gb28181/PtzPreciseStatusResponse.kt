package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig

/**
 * GB/T 28181-2022 A.2.6.15 PTZ 精准状态查询应答。
 *
 * 平台下发 MESSAGE body（A.2.4.13，请求与应答 **CmdType 同名**）:
 * ```xml
 * <Query>
 *   <CmdType>PTZPosition</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 * </Query>
 * ```
 *
 * 设备应答:
 * ```xml
 * <Response>
 *   <CmdType>PTZPosition</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <Pan>123.45</Pan>
 *   <Tilt>-15.00</Tilt>
 *   <Zoom>3.50</Zoom>
 *   <HorizontalFieldAngle>17.14</HorizontalFieldAngle>
 *   <VerticalFieldAngle>10.29</VerticalFieldAngle>
 *   <MaxViewDistance>1050.00</MaxViewDistance>
 * </Response>
 * ```
 *
 * ⛔ **2026-09-19 两处按标准改正**（原先两处都不合 A.2.4.13 / A.2.6.15）:
 *
 *  1. **CmdType 是 `PTZPosition`，不是 `PTZPreciseStatusQuery`。**
 *     `PTZPreciseStatusQuery` 在 2022 全书与 2016 附录 A 里**都是 0 命中**。
 *     后果比"元素名不好看"严重得多：平台按标准发 `<CmdType>PTZPosition</CmdType>` 的
 *     MESSAGE 查询 → 路由 `accepts()` 不认 → **整条查询无人受理**（回了 200 但不处理，
 *     与 `DeviceConfig` 踩过的是同一个坑：两侧日志都正常，只有平台侧表现为"发了没回"）。
 *     路由侧保留旧名只为兼容按旧写法实现的客户端，**标准路径在前**。
 *  2. **六个字段都要发，不是只有 Pan/Tilt/Zoom。**
 *     A.2.6.15 除三轴姿态外还有 `HorizontalFieldAngle` / `VerticalFieldAngle` /
 *     `MaxViewDistance`（六项全部 `minOccurs="0"`，但设备有值就该报）。
 *     这三个光学量由 [com.uvp.sim.domain.toPtzPositionSnapshot] 按变焦倍数算出 ——
 *     与"PTZ 精准位置变化"**订阅通知**（§9.11.2.3 走 [PtzPositionMessage]）用的是同一份，
 *     两条出口给平台的字段集因此一致。
 *
 * 数值两位小数（标准未强制，行业惯例）。
 */
object PtzPreciseStatusResponse {

    /**
     * @param position 六字段姿态快照。**不接受**裸 `PtzPose` —— 那样调用方只能给出三轴，
     *   光学三量会被漏掉（这正是本次要修掉的形态），且"谁来补另外三个"没有明确归属。
     *   由 `DeviceControlModel.toPtzPositionSnapshot()` 统一算，见
     *   [com.uvp.sim.domain.toPtzPositionSnapshot]。
     */
    fun build(
        config: SimConfig,
        sn: String,
        channelId: String,
        position: PtzPositionSnapshot,
    ): String {
        val responseDeviceId = channelId.ifBlank { config.device.deviceId }
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>${PtzPositionMessage.CMD_TYPE}</CmdType>
<SN>$sn</SN>
<DeviceID>$responseDeviceId</DeviceID>
<Pan>${formatTwo(position.pan)}</Pan>
<Tilt>${formatTwo(position.tilt)}</Tilt>
<Zoom>${formatTwo(position.zoom)}</Zoom>
<HorizontalFieldAngle>${formatTwo(position.horizontalFieldAngle)}</HorizontalFieldAngle>
<VerticalFieldAngle>${formatTwo(position.verticalFieldAngle)}</VerticalFieldAngle>
<MaxViewDistance>${formatTwo(position.maxViewDistance)}</MaxViewDistance>
</Response>
""".replace("\n", "\r\n")
    }

    /** KMP 友好的 "%.2f" 替代:Kotlin/Native 不支持 String.format(). */
    internal fun formatTwo(v: Double): String {
        val rounded = kotlin.math.round(v * 100.0).toLong()
        val abs = kotlin.math.abs(rounded)
        val whole = abs / 100
        val frac = abs % 100
        val sign = if (rounded < 0) "-" else ""
        return "$sign$whole.${frac.toString().padStart(2, '0')}"
    }
}

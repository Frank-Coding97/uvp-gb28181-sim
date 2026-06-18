package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.PtzPose

/**
 * GB/T 28181-2022 §9.5.3 A.2.4.13 PTZ 精准状态查询应答构造.
 *
 * 平台下发 MESSAGE body:
 * ```xml
 * <Query>
 *   <CmdType>PTZPreciseStatusQuery</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 * </Query>
 * ```
 *
 * 设备回应当前精确角度(若曾收过 PTZPreciseCtrl)或实时积分值兜底:
 * ```xml
 * <Response>
 *   <CmdType>PTZPreciseStatusQuery</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <Pan>123.45</Pan>
 *   <Tilt>-15.00</Tilt>
 *   <Zoom>3.50</Zoom>
 * </Response>
 * ```
 *
 * 跟 [PtzPreciseCtrlParser] 配套形成 GB-2022 精确控制闭环:
 * - 平台下发 PTZPreciseCtrl → 设备 easeTo target pose + 写 lastPreciseCtrl
 * - 平台查询 PTZPreciseStatusQuery → 设备返回 lastPreciseCtrl
 *
 * 数值用 "%.2f" 保留 2 位小数(标准未强制,但行业惯例).
 */
object PtzPreciseStatusResponse {

    fun build(
        config: SimConfig,
        sn: String,
        channelId: String,
        pose: PtzPose,
    ): String {
        val responseDeviceId = channelId.ifBlank { config.device.deviceId }
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>PTZPreciseStatusQuery</CmdType>
<SN>$sn</SN>
<DeviceID>$responseDeviceId</DeviceID>
<Pan>${formatTwo(pose.pan)}</Pan>
<Tilt>${formatTwo(pose.tilt)}</Tilt>
<Zoom>${formatTwo(pose.zoom)}</Zoom>
</Response>
""".replace("\n", "\r\n")
    }

    /** KMP 友好的 "%.2f" 替代:Kotlin/Native 不支持 String.format(). */
    private fun formatTwo(v: Float): String {
        val rounded = kotlin.math.round(v * 100f).toInt()
        val abs = kotlin.math.abs(rounded)
        val whole = abs / 100
        val frac = abs % 100
        val sign = if (rounded < 0) "-" else ""
        return "$sign$whole.${frac.toString().padStart(2, '0')}"
    }
}

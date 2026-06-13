package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.SimConfig

/**
 * GB/T 28181 §9.3.2 DeviceInfo 应答构造。
 *
 * 平台下发 MESSAGE body:
 * ```xml
 * <Query>
 *   <CmdType>DeviceInfo</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 * </Query>
 * ```
 *
 * 设备回 MESSAGE body:
 * ```xml
 * <Response>
 *   <CmdType>DeviceInfo</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <DeviceName>...</DeviceName>
 *   <Manufacturer>...</Manufacturer>
 *   <Model>...</Model>
 *   <Firmware>...</Firmware>
 *   <HardwareVersion>...</HardwareVersion>   ← GB-2022 新增,GB-2016 无
 *   <Channel>N</Channel>                      ← 通道总数(M2 = 1)
 *   <Result>OK</Result>
 * </Response>
 * ```
 *
 * 注:GB-2016 §9.3.2 只要求到 Firmware + Channel,Hardware 字段是 2022 引入。
 */
object DeviceInfoResponse {

    fun build(config: SimConfig, sn: String): String {
        val device = config.device
        val hardwareLine = if (config.gbVersion == GbVersion.V2022) {
            "<HardwareVersion>${device.hardwareVersion}</HardwareVersion>\n"
        } else {
            ""
        }
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>DeviceInfo</CmdType>
<SN>$sn</SN>
<DeviceID>${device.deviceId}</DeviceID>
<DeviceName>${device.name}</DeviceName>
<Manufacturer>${device.manufacturer}</Manufacturer>
<Model>${device.model}</Model>
<Firmware>${device.firmware}</Firmware>
${hardwareLine}<Channel>1</Channel>
<Result>OK</Result>
</Response>
""".replace("\n", "\r\n")
    }
}

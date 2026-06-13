package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig

/**
 * GB/T 28181 §9.3.4 PresetQuery 应答构造。
 *
 * 平台下发 MESSAGE body:
 * ```xml
 * <Query>
 *   <CmdType>PresetQuery</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>     ← 通道 ID
 * </Query>
 * ```
 *
 * 设备回 MESSAGE body:
 * ```xml
 * <Response>
 *   <CmdType>PresetQuery</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <SumNum>0</SumNum>
 *   <PresetList Num="0"/>
 * </Response>
 * ```
 *
 * M2 模拟器没真 PTZ,返回空 PresetList(SumNum=0)即可,平台合规接受。
 * 后续 M3 上 PTZ CRUD 时再扩展真实预置位项。
 */
object PresetQueryResponse {

    fun build(config: SimConfig, sn: String, channelId: String): String {
        val responseDeviceId = channelId.ifBlank { config.device.deviceId }
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>PresetQuery</CmdType>
<SN>$sn</SN>
<DeviceID>$responseDeviceId</DeviceID>
<SumNum>0</SumNum>
<PresetList Num="0"/>
</Response>
""".replace("\n", "\r\n")
    }
}

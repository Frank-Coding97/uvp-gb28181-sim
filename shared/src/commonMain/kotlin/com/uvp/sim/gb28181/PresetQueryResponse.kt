package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.PtzPose

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
 * 设备回 MESSAGE body(空列表):
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
 * 设备回非空(2026-06-18 T4):
 * ```xml
 * <PresetList Num="2">
 *   <Item><PresetID>1</PresetID><PresetName>Preset 1</PresetName></Item>
 *   <Item><PresetID>3</PresetID><PresetName>Preset 3</PresetName></Item>
 * </PresetList>
 * ```
 *
 * spec Q3 决议:
 * - 上限 8 个 (调用方应已过滤)
 * - 名字默认 `Preset $index`(设备只读,无用户命名入口)
 * - Item 按 index 升序输出
 */
object PresetQueryResponse {

    fun build(
        config: SimConfig,
        sn: String,
        channelId: String,
        presets: Map<Int, PtzPose> = emptyMap(),
    ): String {
        val responseDeviceId = channelId.ifBlank { config.device.deviceId }
        val sumNum = presets.size
        val sortedItems = presets.toSortedMap()
        val itemsBlock = if (sumNum == 0) {
            "<PresetList Num=\"0\"/>"
        } else {
            val items = sortedItems.keys.joinToString("\n") { idx ->
                "<Item><PresetID>$idx</PresetID><PresetName>Preset $idx</PresetName></Item>"
            }
            "<PresetList Num=\"$sumNum\">\n$items\n</PresetList>"
        }
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>PresetQuery</CmdType>
<SN>$sn</SN>
<DeviceID>$responseDeviceId</DeviceID>
<SumNum>$sumNum</SumNum>
$itemsBlock
</Response>
""".replace("\n", "\r\n")
    }
}

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
 *   <Item><PresetID>1</PresetID><PresetName>大门</PresetName></Item>
 *   <Item><PresetID>3</PresetID><PresetName>Preset 3</PresetName></Item>
 * </PresetList>
 * ```
 *
 * ⭐ **2026-09-19：名字优先用平台配的**（A.2.3.1.2 `PTZCmdParams/PresetName`，2022 新增）。
 * 只有平台从没给过名字的预置位才回退设备自造名 `Preset $idx`。
 * 顺序不可颠倒 —— 有平台名还发自造名，平台会以为"自己配的名字没保存"。
 *
 * spec Q3 决议:
 * - 上限 8 个 (调用方应已过滤)
 * - 名字默认 `Preset $index`(设备只读,无用户命名入口)
 * - Item 按 index 升序输出
 */
object PresetQueryResponse {

    /**
     * @param presets 设备上**真实存在**的预置位（编号 → 位姿）。
     * @param presetNames 平台命名的预置位名（编号 → 名字），见
     *   [com.uvp.sim.domain.DeviceControlModel.presetNames]。
     */
    fun build(
        config: SimConfig,
        sn: String,
        channelId: String,
        presets: Map<Int, PtzPose> = emptyMap(),
        presetNames: Map<Int, String> = emptyMap(),
    ): String {
        val responseDeviceId = channelId.ifBlank { config.device.deviceId }
        val sumNum = presets.size
        val sortedKeys = presets.keys.sorted()
        val itemsBlock = if (sumNum == 0) {
            "<PresetList Num=\"0\"/>"
        } else {
            val items = sortedKeys.joinToString("\n") { idx ->
                // `PresetName` 是必选元素（A.2.6.10），所以回退名不是"可选的装饰"。
                // 平台名来自报文，必须转义 —— 名字里带 `&`/`<` 会让整条 Response 变成畸形 XML。
                val name = presetNames[idx] ?: "Preset $idx"
                "<Item><PresetID>$idx</PresetID><PresetName>${escapeXmlText(name)}</PresetName></Item>"
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

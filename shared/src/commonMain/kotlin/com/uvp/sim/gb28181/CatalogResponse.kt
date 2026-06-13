package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig

/**
 * Build the GB/T 28181 Catalog Response body in MANSCDP+xml.
 *
 * Per GB28181 § 9.3.1.3, a Catalog Response from the device contains:
 *   - CmdType=Catalog, SN matching the query
 *   - DeviceID = the device that received the query (usually our deviceId)
 *   - SumNum = total number of items across paginated responses
 *   - DeviceList Num="N" with N <Item> children
 *
 * Two builders:
 *   - [build]: legacy single-channel response,M1 范围。
 *   - [buildFromTree]: M2 范围 — 用动态目录树整棵推过去,跟
 *     [CatalogNotifyBuilder] 用同一份 Item 序列化逻辑保证一致。
 *
 * The format is exactly what WVP-pro / EasyCVR expect. Minor capitalization
 * differences exist between GB-2016 and GB-2022; we emit GB-2022 form here.
 */
object CatalogResponse {

    fun build(
        config: SimConfig,
        sn: String,
        channelName: String = config.device.name
    ): String {
        val device = config.device
        val server = config.server
        // CivilCode 取行政区划前 6 位(domain 前 6 位即可,GB28181 ID 前 6 位也是行政区划)
        val civilCode = server.domain.take(6).padEnd(6, '0')
        return """<?xml version="1.0" encoding="UTF-8"?>
<Response>
<CmdType>Catalog</CmdType>
<SN>$sn</SN>
<DeviceID>${device.deviceId}</DeviceID>
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>${device.videoChannelId}</DeviceID>
<Name>$channelName</Name>
<Manufacturer>UVP</Manufacturer>
<Model>GB28181-Sim</Model>
<Owner>UVP</Owner>
<CivilCode>$civilCode</CivilCode>
<Address>Mobile</Address>
<Parental>0</Parental>
<ParentID>${device.deviceId}</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<Status>ON</Status>
</Item>
</DeviceList>
</Response>
""".replace("\n", "\r\n")
    }

    /**
     * 按当前生效目录树构造 Catalog Response,DFS 序列化跟 NOTIFY 一致。
     * 推荐 M2 之后的 Query 响应路径都走这条。
     */
    fun buildFromTree(
        config: SimConfig,
        sn: String,
        tree: List<CatalogNode>
    ): String = CatalogNotifyBuilder.renderResponse(
        deviceId = config.device.deviceId,
        sn = sn,
        tree = tree
    )
}

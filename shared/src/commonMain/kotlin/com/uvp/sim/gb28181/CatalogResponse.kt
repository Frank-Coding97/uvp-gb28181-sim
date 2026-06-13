package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.SimConfig

/**
 * Build the GB/T 28181 Catalog Response body in MANSCDP+xml.
 *
 * §9.3.1.3,Catalog Response 包含:
 *   - CmdType=Catalog, SN 匹配 Query
 *   - DeviceID = 收到查询的设备(通常 = device.deviceId)
 *   - SumNum = 全部分页总条数
 *   - DeviceList Num="N",其中 N 个 <Item> 通道描述
 *
 * GB-2016 与 GB-2022 差异(§9.3.1):
 *   - GB-2016:Manufacturer / Model / Owner / CivilCode / Address / Parental /
 *             ParentID / SafetyWay / RegisterWay / Secrecy / Status
 *   - GB-2022:在 GB-2016 基础上 **新增 10 个字段**:
 *             IPAddress / Port / PTZType / PositionType / RoomType / UseType /
 *             SupplyLightType / DirectionType / Resolution / BusinessGroupID
 *
 * 实现策略:按 [SimConfig.gbVersion] 分支输出,GB-2016 模式下不发新字段。
 * 通道高级属性来源 [com.uvp.sim.config.ChannelProfile]。
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
        val itemBody = buildItem(config, channelName, civilCode)
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>Catalog</CmdType>
<SN>$sn</SN>
<DeviceID>${device.deviceId}</DeviceID>
<SumNum>1</SumNum>
<DeviceList Num="1">
$itemBody</DeviceList>
</Response>
""".replace("\n", "\r\n")
    }

    /** 单个通道 Item。GB-2016/GB-2022 共用前段,GB-2022 末尾追加 10 个新字段。 */
    private fun buildItem(
        config: SimConfig,
        channelName: String,
        civilCode: String
    ): String {
        val device = config.device
        val baseFields = """<Item>
<DeviceID>${device.videoChannelId}</DeviceID>
<Name>$channelName</Name>
<Manufacturer>${device.manufacturer}</Manufacturer>
<Model>${device.model}</Model>
<Owner>UVP</Owner>
<CivilCode>$civilCode</CivilCode>
<Address>Mobile</Address>
<Parental>0</Parental>
<ParentID>${device.deviceId}</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<Status>ON</Status>
"""
        val extra = if (config.gbVersion == GbVersion.V2022) {
            buildGb2022Fields(config)
        } else {
            ""
        }
        return baseFields + extra + "</Item>\n"
    }

    /**
     * GB/T 28181-2022 §9.3.1 新增字段。
     * 字段值由 [com.uvp.sim.config.ChannelProfile] 提供,默认值在 ChannelProfile 里定义。
     */
    private fun buildGb2022Fields(config: SimConfig): String {
        val ch = config.device.channel
        return """<IPAddress>${ch.ipAddress}</IPAddress>
<Port>${ch.port}</Port>
<PTZType>${ch.ptzType.gbCode}</PTZType>
<PositionType>${ch.positionType.gbCode}</PositionType>
<RoomType>${ch.roomType.gbCode}</RoomType>
<UseType>${ch.useType.gbCode}</UseType>
<SupplyLightType>${ch.supplyLightType.gbCode}</SupplyLightType>
<DirectionType>${ch.directionType.gbCode}</DirectionType>
<Resolution>${ch.resolution}</Resolution>
<BusinessGroupID>${ch.businessGroupId}</BusinessGroupID>
"""
    }
}

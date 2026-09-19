package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.SimConfig

/**
 * GB/T 28181 Catalog Response body(MANSCDP+xml)构建入口。
 *
 * §9.3.1.3,Catalog Response 包含:
 *   - CmdType=Catalog, SN 匹配 Query
 *   - DeviceID = 收到查询的设备(通常 = device.deviceId)
 *   - SumNum = 全部分页总条数
 *   - DeviceList Num="N",其中 N 个 `<Item>` 通道描述
 *
 * ⭐ `<Item>` 的字段内容与**2016/2022 双版本形态**全部由 [CatalogNotifyBuilder] 统一产出 ——
 * 本对象只负责「把目录树交出去 + 选 wrapper 标签」,**不再自己拼 Item**。
 * 这样做的原因:历史上这里有过第二套独立实现(`buildGb2022Fields()`),它把 2022 新增字段
 * 平铺在 `Item` 直接子级,既不符合 2016 也不符合 2022(两版都要求这些字段在 `<Info>` 容器内),
 * 而且与生产路径长期分叉 —— 已删除,避免两套实现各自漂移。
 *
 * 版本形态的权威说明见 [CatalogNotifyBuilder] 的类 KDoc。
 */
object CatalogResponse {

    /**
     * 单通道应答(legacy 便捷入口,主要供测试与预览使用)。
     *
     * 内部构造一个 [CatalogNodeType.VideoChannel] 节点交给 [CatalogNotifyBuilder.renderResponse],
     * 因此输出形态与生产路径(整棵目录树)**完全一致**,不会再出现两套实现漂移。
     *
     * @param version 无默认值必传 —— 见 [CatalogNotifyBuilder.build] 同名参数说明
     */
    fun build(
        config: SimConfig,
        sn: String,
        version: GbVersion,
        channelName: String = config.device.name,
    ): String {
        val node = CatalogNode(
            id = config.device.videoChannelId,
            type = CatalogNodeType.VideoChannel,
            name = channelName,
            parentId = config.device.deviceId,
            fields = mapOf(
                "Manufacturer" to config.device.manufacturer,
                "Model" to config.device.model,
                // CivilCode 取行政区划前 6 位(domain 前 6 位即可,GB28181 ID 前 6 位也是行政区划)
                "CivilCode" to config.server.domain.take(6).padEnd(6, '0'),
                "Status" to "ON",
            ),
        )
        return CatalogNotifyBuilder.renderResponse(
            deviceId = config.device.deviceId,
            sn = sn,
            tree = listOf(node),
            version = version,
            channel = config.device.channel,
        )
    }

    /**
     * 按当前生效目录树构造 Catalog Response,DFS 序列化跟 NOTIFY 一致。
     * 推荐 M2 之后的 Query 响应路径都走这条。
     */
    fun buildFromTree(
        config: SimConfig,
        sn: String,
        tree: List<CatalogNode>,
        version: GbVersion,
    ): String = CatalogNotifyBuilder.renderResponse(
        deviceId = config.device.deviceId,
        sn = sn,
        tree = tree,
        version = version,
        channel = config.device.channel,
    )

    fun buildAllFromTree(
        config: SimConfig,
        sn: String,
        tree: List<CatalogNode>,
        version: GbVersion,
    ): List<String> = CatalogNotifyBuilder.renderResponseAll(
        deviceId = config.device.deviceId,
        sn = sn,
        tree = tree,
        pageSize = config.multiResponsePageSize.coerceIn(1, 10_000),
        version = version,
        channel = config.device.channel,
    )
}

package com.uvp.sim.domain

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.SimConfig

/**
 * 目录树工具:默认树生成 + 取生效树。
 *
 * 当 SimConfig.catalogTree 为空(老 config 升级 / 用户没编辑过),
 * 从 SimConfig.device 字段构造 3 节点扁平树:
 *
 *   设备根 (Device, parentId=自身)
 *   ├── 视频通道 (VideoChannel, parentId=设备根)
 *   └── 报警通道 (AlarmChannel, parentId=设备根)
 */
object CatalogTreeStore {

    fun defaultTree(config: SimConfig): List<CatalogNode> {
        val rootId = config.device.deviceId
        val root = CatalogNode(
            id = rootId,
            type = CatalogNodeType.Device,
            name = config.device.name,
            parentId = rootId,
            fields = mapOf(
                "Manufacturer" to "UVP",
                "Model" to "UVP-Sim",
                "Status" to "ON"
            )
        )
        val video = CatalogNode(
            id = config.device.videoChannelId,
            type = CatalogNodeType.VideoChannel,
            name = "${config.device.name}-视频",
            parentId = rootId,
            fields = mapOf(
                "Manufacturer" to "UVP",
                "Model" to "UVP-Sim",
                "Status" to "ON"
            )
        )
        val alarm = CatalogNode(
            id = config.device.alarmChannelId,
            type = CatalogNodeType.AlarmChannel,
            name = "${config.device.name}-报警",
            parentId = rootId,
            fields = mapOf("Status" to "ON")
        )
        return listOf(root, video, alarm)
    }

    fun effectiveTree(config: SimConfig): List<CatalogNode> =
        if (config.catalogTree.isEmpty()) defaultTree(config) else config.catalogTree
}

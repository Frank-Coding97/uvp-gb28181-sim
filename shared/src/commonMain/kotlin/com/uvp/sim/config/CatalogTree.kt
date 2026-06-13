package com.uvp.sim.config

import kotlinx.serialization.Serializable

/**
 * GB/T 28181-2022 §A.3 节点类型码。
 * - typeCode: 20 位国标编码中第 11-13 位的类型码
 * - parental: 1 表示有子(目录节点),0 表示叶子(通道)
 */
@Serializable
enum class CatalogNodeType(val typeCode: String, val parental: Int) {
    Device("111", 1),
    BusinessGroup("137", 1),
    VirtualOrg("138", 1),
    VideoChannel("132", 0),
    AlarmChannel("134", 0)
}

/**
 * 设备目录树节点。根节点(Device)的 parentId 指向自身。
 * fields 容纳国标 Item 中可选字段:Manufacturer / Model / Owner / CivilCode /
 * Address / SafetyWay / RegisterWay / Secrecy / Status 等。
 */
@Serializable
data class CatalogNode(
    val id: String,
    val type: CatalogNodeType,
    val name: String,
    val parentId: String,
    val fields: Map<String, String> = emptyMap()
)

package com.uvp.sim.domain

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.SimConfig

/**
 * 目录树工具:默认树生成 + 取生效树 + 校验。
 *
 * 当 SimConfig.catalogTree 为空(老 config 升级 / 用户没编辑过),
 * 从 SimConfig.device 字段构造设备视角的扁平树:
 *
 *   设备根 (Device, parentId=自身)
 *   ├── 视频通道 (VideoChannel, parentId=设备根)
 *   └── 报警通道 (AlarmChannel, parentId=设备根)
 */
object CatalogTreeStore {

    /**
     * 返回位置上报应使用的目录通道编码。
     * 配置迁移或用户编辑目录后,DeviceConfig.videoChannelId 可能已经过期,
     * 所以优先保留仍存在的配置通道,否则选择当前树中的第一个视频通道。
     */
    fun positionChannelId(config: SimConfig, tree: List<CatalogNode> = effectiveTree(config)): String {
        val videoChannels = tree.filter { it.type == CatalogNodeType.VideoChannel }
        return videoChannels.firstOrNull { it.id == config.device.videoChannelId }?.id
            ?: videoChannels.firstOrNull()?.id
            ?: config.device.videoChannelId
    }

    fun defaultTree(config: SimConfig): List<CatalogNode> {
        val rootId = standardDeviceId(config.device.deviceId)
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
        val channelFields = mapOf(
            "Manufacturer" to "UVP",
            "Model" to "UVP-Sim",
            "Status" to "ON"
        )
        // 后置(沿用 videoChannelId,老配置兼容)
        val back = CatalogNode(
            id = config.device.videoChannelId,
            type = CatalogNodeType.VideoChannel,
            name = config.device.videoChannelName,
            parentId = rootId,
            fields = channelFields
        )
        val alarm = CatalogNode(
            id = config.device.alarmChannelId,
            type = CatalogNodeType.AlarmChannel,
            name = "${config.device.name}-报警",
            parentId = rootId,
            fields = mapOf("Status" to "ON")
        )
        // 前置 — frontChannelId 为空(老配置升级)时回退为单后置通道,绝不生成空 ID 节点。
        val frontId = config.device.frontChannelId
        return if (frontId.isBlank()) {
            listOf(root, back, alarm)
        } else {
            val front = CatalogNode(
                id = frontId,
                type = CatalogNodeType.VideoChannel,
                name = config.device.frontChannelName,
                parentId = rootId,
                fields = channelFields
            )
            listOf(root, front, back, alarm)
        }
    }

    fun effectiveTree(config: SimConfig): List<CatalogNode> =
        if (config.catalogTree.isEmpty()) defaultTree(config) else config.catalogTree

    /**
     * GB/T 28181-2022 附录 J 的标准下级平台业务目录样例。
     *
     * 该模板是显式应用的演示场景，不会参与老配置自动迁移：系统(200)作为目录根，
     * 业务分组(215)挂在系统下，首层虚拟组织(216)只用 BusinessGroupID 关联分组，
     * 下级虚拟组织用 ParentID 指向上级虚拟组织并同时携带 BusinessGroupID。
     * 两个真实视频通道沿用设备配置中的 ID，分别挂到两个虚拟组织，并不创建虚拟媒体。
     */
    fun standardBusinessGroupTemplate(config: SimConfig): List<CatalogNode> {
        val systemId = standardSystemId(config.device.deviceId)
        val domain = systemId.take(10)
        val groupId = com.uvp.sim.gb28181.IdEncoder.genChildId(domain, CatalogNodeType.BusinessGroup, 1)
        val firstOrgId = com.uvp.sim.gb28181.IdEncoder.genChildId(domain, CatalogNodeType.VirtualOrg, 1)
        val nestedOrgId = com.uvp.sim.gb28181.IdEncoder.genChildId(domain, CatalogNodeType.VirtualOrg, 2)
        val civilCode = domain.take(6)
        val systemFields = mapOf(
            "Manufacturer" to "UVP",
            "Model" to "UVP-Sim",
            "CivilCode" to civilCode,
            "Status" to "ON",
        )
        return buildList {
            add(CatalogNode(systemId, CatalogNodeType.System, "${config.device.name}-下级平台", systemId, systemFields))
            add(CatalogNode(
                groupId,
                CatalogNodeType.BusinessGroup,
                "校园安防",
                systemId,
                mapOf("CivilCode" to civilCode),
            ))
            add(CatalogNode(
                firstOrgId, CatalogNodeType.VirtualOrg, "出入口", "",
                mapOf("BusinessGroupID" to groupId)
            ))
            add(CatalogNode(
                nestedOrgId, CatalogNodeType.VirtualOrg, "教学区域", firstOrgId,
                mapOf("BusinessGroupID" to groupId)
            ))
            if (config.device.frontChannelId.isNotBlank()) {
                add(CatalogNode(
                    config.device.frontChannelId, CatalogNodeType.VideoChannel,
                    config.device.frontChannelName, "$systemId/$firstOrgId",
                    mapOf("Manufacturer" to "UVP", "Model" to "UVP-Sim", "Status" to "ON")
                ))
            }
            add(CatalogNode(
                config.device.videoChannelId, CatalogNodeType.VideoChannel,
                config.device.videoChannelName, "$systemId/$nestedOrgId",
                mapOf("Manufacturer" to "UVP", "Model" to "UVP-Sim", "Status" to "ON")
            ))
        }
    }

    /** 从当前设备编码派生同一序号的系统(200)编码，供显式标准模板使用。 */
    fun standardSystemId(deviceId: String): String {
        require(deviceId.length == 20 && deviceId.all { it in '0'..'9' }) {
            "deviceId must be a 20-digit GB identifier"
        }
        return deviceId.take(10) + CatalogNodeType.System.typeCode + deviceId.takeLast(7)
    }

    /** 将标准 System(200) 注册编码映射回默认设备(111)编码。 */
    fun standardDeviceId(systemId: String): String {
        require(systemId.length == 20 && systemId.all { it in '0'..'9' }) {
            "systemId must be a 20-digit GB identifier"
        }
        return if (systemId.substring(10, 13) == CatalogNodeType.System.typeCode) {
            systemId.take(10) + CatalogNodeType.Device.typeCode + systemId.takeLast(7)
        } else {
            systemId
        }
    }

    /** P2-1 预设目录模板:默认单设备结构和 GB/T 28181-2022 标准业务分组样例。 */
    fun templates(config: SimConfig): List<CatalogTemplate> {
        val rootId = standardDeviceId(config.device.deviceId)
        val name = config.device.name
        val singleNodes = defaultTree(config)
        val singleVideoChannelCount = singleNodes.count { it.type == CatalogNodeType.VideoChannel }

        val result = mutableListOf(
            CatalogTemplate(
                id = "single",
                title = "单设备(默认)",
                description = "1 设备 + $singleVideoChannelCount 视频通道 + 1 报警通道。最简结构。",
                nodes = singleNodes,
                registrationDeviceId = rootId,
            ),
        )
        if (config.gbVersion == com.uvp.sim.config.GbVersion.V2022) {
            result += CatalogTemplate(
                id = "standard-business-group",
                title = "标准业务分组(下级平台)",
                description = "系统(200) → 业务分组(215) → 虚拟组织(216) → 两个真实视频通道；应用时切换注册身份为系统编码。",
                nodes = standardBusinessGroupTemplate(config),
                registrationDeviceId = standardSystemId(config.device.deviceId),
            )
        }
        return result
    }

    /**
     * P1-3: 树差异算法。返回从 [old] 到 [new] 需要应用的变更事件列表。
     *
     * - 在 new 但不在 old → Add
     * - 在 old 但不在 new → Del
     * - 两边都有但内容不一致 → Update
     *
     * 顺序:Add 按 new 出现顺序,Update 按 new 出现顺序,Del 按 old 出现顺序。
     */
    fun diff(
        old: List<CatalogNode>,
        new: List<CatalogNode>
    ): List<CatalogChangeEvent> {
        val oldById = old.associateBy { it.id }
        val newById = new.associateBy { it.id }
        val events = mutableListOf<CatalogChangeEvent>()
        // Add / Update — 按 new 顺序
        for (n in new) {
            val o = oldById[n.id]
            if (o == null) events += CatalogChangeEvent.Add(n)
            else if (o != n) events += CatalogChangeEvent.Update(n)
        }
        // Del — 按 old 顺序
        for (o in old) {
            if (o.id !in newById) events += CatalogChangeEvent.Del(o.id)
        }
        return events
    }

    /**
     * 决定增量 vs 全量 NOTIFY:
     *   - 老树为空(initial) → 全量
     *   - 变更节点 / 老树节点 > 30% → 全量
     *   - 总变更 ≥ 20 → 全量
     *   - 否则 → 增量(节省带宽,且 WVP 处理 incremental 比较平稳)
     */
    fun shouldUseIncremental(
        events: List<CatalogChangeEvent>,
        oldSize: Int
    ): Boolean {
        if (events.isEmpty()) return false       // 没变更不发,这里返回 false 更安全
        if (oldSize == 0) return false           // 第一次必须全量
        if (events.size >= 20) return false      // 大批量改动直接全量
        val ratio = events.size.toDouble() / oldSize
        return ratio < 0.30
    }

    /**
     * P1-4: 树合法性校验。返回 [ValidationResult.Ok] 或 [ValidationResult.Invalid] 含错误清单。
     *
     * 校验项:
     * 1. 节点 ID 不为空
     * 2. ID 不重复
     * 3. 必须有且仅有一个 Device 根节点或 System 根节点(parentId=自身)
     * 4. 非根节点的父关系必须存在；首层 216 可通过 BusinessGroupID 关联 215
     * 5. 不能有循环引用(从根 DFS 能到达所有节点,且不形成环)
     * 6. 名字不为空
     */
    fun validate(tree: List<CatalogNode>): ValidationResult {
        if (tree.isEmpty()) return ValidationResult.Ok

        val errors = mutableListOf<String>()

        // 1+2. 空 ID / 重复 ID
        val seen = mutableSetOf<String>()
        val duplicates = mutableSetOf<String>()
        for (n in tree) {
            if (n.id.isBlank()) {
                errors += "节点 ID 不能为空(name=${n.name})"
                continue
            }
            if (n.name.isBlank()) {
                errors += "节点名字不能为空(id=${n.id})"
            }
            if (n.id in seen) duplicates += n.id else seen += n.id
        }
        if (duplicates.isNotEmpty()) {
            errors += "节点 ID 重复:${duplicates.joinToString(", ")}"
        }

        // 3. 唯一 Device/System 根。普通 IPC 使用 Device，标准下级平台使用 System。
        val roots = tree.filter { it.type == CatalogNodeType.Device || it.type == CatalogNodeType.System }
        when {
            roots.isEmpty() -> errors += "缺少设备根节点(Device)或系统根节点(System 类型)"
            roots.size > 1 -> errors += "目录只能有一个 Device 或 System 根节点,当前 ${roots.size} 个"
            roots.first().parentId != roots.first().id ->
                errors += "目录根节点的 parentId 必须指向自身"
        }
        if (roots.size == 1 && roots.first().type == CatalogNodeType.System) {
            tree.forEach { node ->
                val expectedTypeCode = when (node.type) {
                    CatalogNodeType.System -> "200"
                    CatalogNodeType.Device -> "111"
                    CatalogNodeType.BusinessGroup -> "215"
                    CatalogNodeType.VirtualOrg -> "216"
                    CatalogNodeType.VideoChannel -> "132"
                    CatalogNodeType.AlarmChannel -> "134"
                    CatalogNodeType.AdministrativeRegion -> null
                }
                if (expectedTypeCode != null &&
                    (node.id.length != 20 || node.id.any { it !in '0'..'9' } ||
                        node.id.substring(10, 13) != expectedTypeCode)
                ) {
                    errors += "${node.type} 节点 ${node.name}(${node.id}) 必须使用类型码为 $expectedTypeCode 的 20 位数字编码"
                }
            }
        }

        // 4. 非根节点的父关系必须存在。ParentID 可用 '/' 表示系统/虚拟组织双重归属。
        val ids = tree.mapTo(mutableSetOf()) { it.id }
        val byId = tree.associateBy { it.id }
        val hasSystemRoot = roots.singleOrNull()?.type == CatalogNodeType.System
        for (n in tree) {
            val groupId = n.fields["BusinessGroupID"]
            if (groupId != null) {
                if (n.type !in setOf(
                        CatalogNodeType.VirtualOrg,
                        CatalogNodeType.VideoChannel,
                        CatalogNodeType.Device,
                    )
                ) {
                    errors += "节点 ${n.name}(${n.id}) 的类型 ${n.type} 不应携带 BusinessGroupID"
                }
                val group = byId[groupId]
                if (group == null) {
                    errors += "节点 ${n.name}(${n.id}) 的 BusinessGroupID=$groupId 不存在"
                } else if (group.type != CatalogNodeType.BusinessGroup) {
                    errors += "节点 ${n.name}(${n.id}) 的 BusinessGroupID=$groupId 不是业务分组(215)"
                }
            }
            if (n.parentId == n.id) continue // 根节点
            val parentIds = parentIds(n)
            if (parentIds.isEmpty()) {
                if (n.type == CatalogNodeType.VirtualOrg) {
                    val group = groupId?.let { byId[it] }
                    val groupParents = group?.let {
                        parentIds(it).mapNotNull { parentId -> byId[parentId] }
                    }.orEmpty()
                    if (group?.type == CatalogNodeType.BusinessGroup &&
                        (!hasSystemRoot || (groupParents.size == 1 && groupParents.single().type == CatalogNodeType.System))
                    ) {
                        continue // 附录 J 首层 216：不写 ParentID，使用 BusinessGroupID 归属 215
                    }
                    if (groupId != null && groupId !in ids) {
                        errors += "节点 ${n.name}(${n.id}) 的 BusinessGroupID=$groupId 不存在"
                    } else if (groupId != null) {
                        errors += "节点 ${n.name}(${n.id}) 的 BusinessGroupID=$groupId 不是业务分组(215)"
                    } else {
                        errors += "节点 ${n.name}(${n.id}) 的 parentId 不能为空"
                    }
                    continue
                }
                errors += "节点 ${n.name}(${n.id}) 的 parentId 不能为空"
                continue
            }
            val missing = parentIds.filter { it !in ids }
            if (missing.isNotEmpty()) {
                errors += "节点 ${n.name}(${n.id}) 的父节点=${missing.joinToString("/")} 不存在"
            }
            parentIds.mapNotNull { byId[it] }.forEach { parent ->
                val standardRelation = isStandardBusinessRelation(n, parent, byId)
                if (!isAllowedParent(n.type, parent.type) || standardRelation == false) {
                    errors += "节点 ${n.name}(${n.id}) 的父节点 ${parent.name}(${parent.id}) 类型不合法"
                }
            }
            if (hasSystemRoot && n.type == CatalogNodeType.VirtualOrg && parentIds.isNotEmpty()) {
                val group = groupId?.let { byId[it] }
                if (group?.type != CatalogNodeType.BusinessGroup) {
                    errors += "节点 ${n.name}(${n.id}) 的虚拟组织必须关联有效业务分组(215)"
                }
                if (parentIds.any { byId[it]?.type == CatalogNodeType.BusinessGroup }) {
                    errors += "节点 ${n.name}(${n.id}) 的首层虚拟组织不应通过 ParentID 直接挂业务分组"
                }
                parentIds.mapNotNull { byId[it] }
                    .filter { it.type == CatalogNodeType.VirtualOrg }
                    .forEach { parentOrg ->
                        if (parentOrg.fields["BusinessGroupID"] != groupId) {
                            errors += "节点 ${n.name}(${n.id}) 的 BusinessGroupID 必须与父虚拟组织一致"
                        }
                    }
            }
        }

        // 5. 循环检测(沿 ParentID 及首层216的 BusinessGroupID 向上)
        for (n in tree) {
            if (hasCycle(n, byId, mutableSetOf())) {
                errors += "节点 ${n.name}(${n.id}) 存在循环引用"
            }
        }

        return if (errors.isEmpty()) ValidationResult.Ok
        else ValidationResult.Invalid(errors.distinct())
    }

    private fun hasCycle(node: CatalogNode, byId: Map<String, CatalogNode>, path: MutableSet<String>): Boolean {
        if (!path.add(node.id)) return true
        if (node.parentId == node.id) {
            path.remove(node.id)
            return false
        }
        val parents = parentIds(node).ifEmpty {
            if (node.type == CatalogNodeType.VirtualOrg) {
                listOfNotNull(node.fields["BusinessGroupID"]?.takeIf { it.isNotBlank() })
            } else emptyList()
        }
        val result = parents.any { byId[it]?.let { parent -> hasCycle(parent, byId, path) } == true }
        path.remove(node.id)
        return result
    }

    private fun parentIds(node: CatalogNode): List<String> =
        node.parentId.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != node.id }

    private fun isAllowedParent(child: CatalogNodeType, parent: CatalogNodeType): Boolean = when (child) {
        CatalogNodeType.BusinessGroup -> parent == CatalogNodeType.System || parent == CatalogNodeType.Device
        CatalogNodeType.VirtualOrg -> parent == CatalogNodeType.VirtualOrg || parent == CatalogNodeType.BusinessGroup ||
            parent == CatalogNodeType.System || parent == CatalogNodeType.Device
        CatalogNodeType.VideoChannel, CatalogNodeType.AlarmChannel -> parent == CatalogNodeType.VirtualOrg ||
            parent == CatalogNodeType.Device || parent == CatalogNodeType.System
        CatalogNodeType.Device, CatalogNodeType.System, CatalogNodeType.AdministrativeRegion -> true
    }

    /**
     * 标准业务目录的强关系约束；普通 Device 目录仍允许历史配置中的扁平关系。
     * 返回 null 表示该父子关系不属于业务分组场景，不额外收紧。
     */
    private fun isStandardBusinessRelation(
        child: CatalogNode,
        parent: CatalogNode,
        byId: Map<String, CatalogNode>,
    ): Boolean? {
        if (byId.values.none { it.type == CatalogNodeType.System }) return null
        return when (child.type) {
            CatalogNodeType.BusinessGroup -> parent.type == CatalogNodeType.System
            CatalogNodeType.VirtualOrg -> parent.type == CatalogNodeType.VirtualOrg
            else -> null
        }
    }
}

sealed class ValidationResult {
    object Ok : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult() {
        val message: String get() = errors.joinToString("\n")
    }

    val isOk: Boolean get() = this is Ok
}

data class CatalogTemplate(
    val id: String,
    val title: String,
    val description: String,
    val nodes: List<CatalogNode>,
    /** 非空表示应用模板时需要同步切换 SIP 注册身份；普通模板保持 null。 */
    val registrationDeviceId: String? = null,
)

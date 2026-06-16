package com.uvp.sim.domain

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.SimConfig

/**
 * 目录树工具:默认树生成 + 取生效树 + 校验。
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
     * P2-1 预设模板:演示间快速切换不同客户场景。
     * 模板用 `config.server.domain` 和 `config.device.{deviceId,name}` 作为基底,
     * 通过 IdEncoder 自动生成各类型 channelId。
     */
    fun templates(config: SimConfig): List<CatalogTemplate> {
        val rootId = config.device.deviceId
        val name = config.device.name
        val domain = config.server.domain

        fun id(type: CatalogNodeType, seq: Int) =
            com.uvp.sim.gb28181.IdEncoder.genChildId(domain, type, seq)

        fun root() = CatalogNode(
            rootId, CatalogNodeType.Device, name, rootId,
            mapOf("Manufacturer" to "UVP", "Model" to "UVP-Sim", "Status" to "ON")
        )

        return listOf(
            CatalogTemplate(
                id = "single",
                title = "单设备(默认)",
                description = "1 设备 + 1 视频通道 + 1 报警通道。最简结构。",
                nodes = defaultTree(config)
            ),
            CatalogTemplate(
                id = "nvr-8ch",
                title = "8 通道 NVR",
                description = "1 设备根 + 1 业务分组「NVR-8」+ 8 个视频通道。",
                nodes = buildList {
                    add(root())
                    val groupId = id(CatalogNodeType.BusinessGroup, 1)
                    add(CatalogNode(groupId, CatalogNodeType.BusinessGroup, "NVR-8", rootId))
                    for (i in 1..8) {
                        val ch = id(CatalogNodeType.VideoChannel, i)
                        add(CatalogNode(
                            ch, CatalogNodeType.VideoChannel,
                            "通道-${i.toString().padStart(2, '0')}", groupId,
                            mapOf("Manufacturer" to "UVP", "Status" to "ON")
                        ))
                    }
                }
            ),
            CatalogTemplate(
                id = "civil-3x2",
                title = "跨区划演示(3 区 × 2 通道)",
                description = "1 设备根 + 3 个虚拟组织(行政区划)各挂 2 个视频通道,演示 CivilCode 分组。",
                nodes = buildList {
                    add(root())
                    val areas = listOf("浦东" to "310115", "黄浦" to "310101", "徐汇" to "310104")
                    var chSeq = 1
                    for ((idx, area) in areas.withIndex()) {
                        val orgId = id(CatalogNodeType.VirtualOrg, idx + 1)
                        add(CatalogNode(
                            orgId, CatalogNodeType.VirtualOrg,
                            "${area.first}区", rootId,
                            mapOf("CivilCode" to area.second, "Status" to "ON")
                        ))
                        for (k in 1..2) {
                            val ch = id(CatalogNodeType.VideoChannel, chSeq++)
                            add(CatalogNode(
                                ch, CatalogNodeType.VideoChannel,
                                "${area.first}-${k}号", orgId,
                                mapOf("CivilCode" to area.second, "Status" to "ON")
                            ))
                        }
                    }
                }
            ),
            CatalogTemplate(
                id = "large-16ch",
                title = "16 通道大型监控",
                description = "1 设备 + 2 业务分组(室内/室外)各 8 通道 + 1 个报警通道总挂根下。",
                nodes = buildList {
                    add(root())
                    val indoor = id(CatalogNodeType.BusinessGroup, 1)
                    val outdoor = id(CatalogNodeType.BusinessGroup, 2)
                    add(CatalogNode(indoor, CatalogNodeType.BusinessGroup, "室内监控", rootId))
                    add(CatalogNode(outdoor, CatalogNodeType.BusinessGroup, "室外监控", rootId))
                    var chSeq = 1
                    for (i in 1..8) {
                        val ch = id(CatalogNodeType.VideoChannel, chSeq++)
                        add(CatalogNode(
                            ch, CatalogNodeType.VideoChannel,
                            "室内-${i.toString().padStart(2, '0')}", indoor
                        ))
                    }
                    for (i in 1..8) {
                        val ch = id(CatalogNodeType.VideoChannel, chSeq++)
                        add(CatalogNode(
                            ch, CatalogNodeType.VideoChannel,
                            "室外-${i.toString().padStart(2, '0')}", outdoor
                        ))
                    }
                    val alarm = id(CatalogNodeType.AlarmChannel, 1)
                    add(CatalogNode(alarm, CatalogNodeType.AlarmChannel, "总报警", rootId))
                }
            )
        )
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
     * 3. 必须有且仅有一个 Device 根节点(parentId=自身)
     * 4. 非根节点的 parentId 必须存在
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

        // 3. 唯一 Device 根
        val devices = tree.filter { it.type == CatalogNodeType.Device }
        when {
            devices.isEmpty() -> errors += "缺少设备根节点(Device 类型)"
            devices.size > 1 -> errors +=
                "设备根节点 (Device) 应该只有 1 个,当前 ${devices.size} 个"
            devices.first().parentId != devices.first().id ->
                errors += "设备根节点的 parentId 必须指向自身"
        }

        // 4. 非根节点的 parentId 必须存在
        val ids = tree.mapTo(mutableSetOf()) { it.id }
        for (n in tree) {
            if (n.parentId == n.id) continue  // 根节点
            if (n.parentId.isBlank()) {
                errors += "节点 ${n.name}(${n.id}) 的 parentId 不能为空"
                continue
            }
            if (n.parentId !in ids) {
                errors += "节点 ${n.name}(${n.id}) 的 parentId=${n.parentId} 不存在"
            }
        }

        // 5. 循环检测(从每个节点向上找根,如果走过的 id 重复出现就是循环)
        val byId = tree.associateBy { it.id }
        for (n in tree) {
            if (hasCycle(n, byId)) {
                errors += "节点 ${n.name}(${n.id}) 存在循环引用"
            }
        }

        return if (errors.isEmpty()) ValidationResult.Ok
        else ValidationResult.Invalid(errors.distinct())
    }

    private fun hasCycle(start: CatalogNode, byId: Map<String, CatalogNode>): Boolean {
        val seen = mutableSetOf<String>()
        var cur: CatalogNode? = start
        while (cur != null) {
            if (cur.id in seen) return true
            seen += cur.id
            if (cur.parentId == cur.id) return false  // 到根
            cur = byId[cur.parentId] ?: return false  // 找不到父,在前面 (4) 已报错
        }
        return false
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
    val nodes: List<CatalogNode>
)

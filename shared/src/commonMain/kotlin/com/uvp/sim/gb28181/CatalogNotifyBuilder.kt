package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType

/**
 * 构造 GB/T 28181 Catalog NOTIFY body(MANSCDP+xml,GB2312)。
 *
 * 跟 [CatalogResponse.build] 区别:
 *   - 顶层是 `<Notify>`(不是 `<Response>`)
 *   - 节点列表来自动态目录树,不是单通道硬编码
 *   - 输出顺序:深度优先(父先于子),从根开始 DFS
 *
 * 字段映射:
 *   - Parental: 来自 CatalogNodeType.parental
 *   - ParentID: 根节点指向自身,其余指向 parentId
 *   - 其它字段从 CatalogNode.fields 取,缺失给保守默认
 */
object CatalogNotifyBuilder {

    fun build(
        deviceId: String,
        sn: Int,
        tree: List<CatalogNode>
    ): String = renderEnvelope(
        wrapperTag = "Notify",
        deviceId = deviceId,
        sn = sn.toString(),
        tree = tree
    )

    /**
     * P1-3 GB §9.3.1.4 增量 NOTIFY:body 顶层仍是 `<Notify><CmdType>Catalog</CmdType>`,
     * 但每个 Item 多一个 `<Event>ADD|DEL|UPDATE</Event>` 子标签。
     *
     * - Add:Item 含完整字段(跟全量 NOTIFY Item 相同) + `<Event>ADD</Event>`
     * - Update:Item 含完整新字段 + `<Event>UPDATE</Event>`
     * - Del:Item 只有 `<DeviceID>` + `<Event>DEL</Event>`(其它字段省略)
     */
    fun buildIncremental(
        deviceId: String,
        sn: Int,
        events: List<CatalogChangeEvent>
    ): String {
        val items = events.joinToString(separator = "\n") { renderEventItem(it) }
        val sumNum = events.size

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<Notify>\n")
        sb.append("<CmdType>Catalog</CmdType>\n")
        sb.append("<SN>").append(sn).append("</SN>\n")
        sb.append("<DeviceID>").append(escapeXmlText(deviceId)).append("</DeviceID>\n")
        sb.append("<SumNum>").append(sumNum).append("</SumNum>\n")
        if (events.isEmpty()) {
            sb.append("<DeviceList Num=\"0\"></DeviceList>\n")
        } else {
            sb.append("<DeviceList Num=\"").append(sumNum).append("\">\n")
            sb.append(items).append("\n")
            sb.append("</DeviceList>\n")
        }
        sb.append("</Notify>\n")
        return sb.toString().replace("\n", "\r\n")
    }

    /**
     * M5 batch2 §7.10 GB §9.3.1.4 通道在线状态简化 NOTIFY。
     *
     * 跟 [buildIncremental] 区别:Item 仅含 `DeviceID + Event(ON|OFF) + Status`,
     * **不含** Manufacturer / Model / Owner / Address / Parental / SafetyWay / RegisterWay / Secrecy。
     * 平台据此只更新该单通道在线状态,不必 fan-out 全字段 UPDATE 包。
     *
     * @param deviceId  设备 ID(顶层 DeviceID)
     * @param sn        SN 自增序号
     * @param channelId 变更状态的通道 ID(Item.DeviceID)
     * @param online    true=ON / false=OFF
     */
    fun buildStatusOnly(
        deviceId: String,
        sn: Int,
        channelId: String,
        online: Boolean
    ): String {
        val event = if (online) "ON" else "OFF"
        val status = if (online) "ON" else "OFF"
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<Notify>\n")
        sb.append("<CmdType>Catalog</CmdType>\n")
        sb.append("<SN>").append(sn).append("</SN>\n")
        sb.append("<DeviceID>").append(escapeXmlText(deviceId)).append("</DeviceID>\n")
        sb.append("<SumNum>1</SumNum>\n")
        sb.append("<DeviceList Num=\"1\">\n")
        sb.append("<Item>\n")
        sb.append("<DeviceID>").append(escapeXmlText(channelId)).append("</DeviceID>\n")
        sb.append("<Event>").append(event).append("</Event>\n")
        sb.append("<Status>").append(status).append("</Status>\n")
        sb.append("</Item>\n")
        sb.append("</DeviceList>\n")
        sb.append("</Notify>\n")
        return sb.toString().replace("\n", "\r\n")
    }

    private fun renderEventItem(event: CatalogChangeEvent): String {
        return when (event) {
            is CatalogChangeEvent.Add -> renderItemWithEvent(event.node, "ADD")
            is CatalogChangeEvent.Update -> renderItemWithEvent(event.node, "UPDATE")
            is CatalogChangeEvent.Del -> {
                val sb = StringBuilder()
                sb.append("<Item>\n")
                sb.append("<DeviceID>").append(escapeXmlText(event.id)).append("</DeviceID>\n")
                sb.append("<Event>DEL</Event>")
                sb.append("\n</Item>")
                sb.toString()
            }
        }
    }

    private fun renderItemWithEvent(node: CatalogNode, eventTag: String): String {
        val full = renderItem(node)
        // 在 </Item> 之前插入 <Event>...</Event>
        val eventLine = "<Event>$eventTag</Event>\n"
        return full.replace("</Item>", "${eventLine}</Item>")
    }

    /**
     * 给 [CatalogResponse.buildFromTree] 用 — 同样的 DFS 序列化,
     * 顶层 wrapper 是 Response 而不是 Notify。
     */
    internal fun renderResponse(
        deviceId: String,
        sn: String,
        tree: List<CatalogNode>
    ): String = renderEnvelope(
        wrapperTag = "Response",
        deviceId = deviceId,
        sn = sn,
        tree = tree
    )

    private fun renderEnvelope(
        wrapperTag: String,
        deviceId: String,
        sn: String,
        tree: List<CatalogNode>
    ): String {
        val ordered = orderDfs(tree)
        val items = ordered.joinToString(separator = "\n") { renderItem(it) }
        val sumNum = ordered.size

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<").append(wrapperTag).append(">\n")
        sb.append("<CmdType>Catalog</CmdType>\n")
        sb.append("<SN>").append(sn).append("</SN>\n")
        sb.append("<DeviceID>").append(escapeXmlText(deviceId)).append("</DeviceID>\n")
        sb.append("<SumNum>").append(sumNum).append("</SumNum>\n")
        if (ordered.isEmpty()) {
            sb.append("<DeviceList Num=\"0\"></DeviceList>\n")
        } else {
            sb.append("<DeviceList Num=\"").append(sumNum).append("\">\n")
            sb.append(items).append("\n")
            sb.append("</DeviceList>\n")
        }
        sb.append("</").append(wrapperTag).append(">\n")
        return sb.toString().replace("\n", "\r\n")
    }

    /**
     * 深度优先排序:根节点先,再按 children 出现顺序递归。
     * 孤儿节点(parentId 找不到对应父)按原顺序追加在末尾,保证不丢节点。
     */
    private fun orderDfs(tree: List<CatalogNode>): List<CatalogNode> {
        if (tree.isEmpty()) return emptyList()
        val byParent = tree.groupBy { it.parentId }
        val nodeIds = tree.mapTo(mutableSetOf()) { it.id }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<CatalogNode>()

        // 根 = parentId 指向自身 OR parentId 不在节点集合里
        val roots = tree.filter { it.parentId == it.id || it.parentId !in nodeIds }
        roots.forEach { dfs(it, byParent, visited, result) }

        // 兜底:还有没访问到的(循环引用 / 孤儿),按原顺序追加
        tree.filter { it.id !in visited }.forEach {
            visited += it.id
            result += it
        }
        return result
    }

    private fun dfs(
        node: CatalogNode,
        byParent: Map<String, List<CatalogNode>>,
        visited: MutableSet<String>,
        out: MutableList<CatalogNode>
    ) {
        if (node.id in visited) return
        visited += node.id
        out += node
        // 跳过自指向(根 parentId=自身)避免无限递归
        byParent[node.id].orEmpty().filter { it.id != node.id }.forEach {
            dfs(it, byParent, visited, out)
        }
    }

    private fun renderItem(node: CatalogNode): String {
        val f = node.fields
        val parentId = if (node.parentId == node.id) node.id else node.parentId
        val sb = StringBuilder()
        sb.append("<Item>\n")
        sb.append("<DeviceID>").append(escapeXmlText(node.id)).append("</DeviceID>\n")
        sb.append("<Name>").append(escapeXmlText(node.name)).append("</Name>\n")
        sb.append("<Manufacturer>").append(escapeXmlText(f["Manufacturer"] ?: "UVP")).append("</Manufacturer>\n")
        sb.append("<Model>").append(escapeXmlText(f["Model"] ?: "UVP-Sim")).append("</Model>\n")
        sb.append("<Owner>").append(escapeXmlText(f["Owner"] ?: "UVP")).append("</Owner>\n")
        sb.append("<CivilCode>").append(escapeXmlText(f["CivilCode"] ?: node.id.take(6))).append("</CivilCode>\n")
        if (node.type == CatalogNodeType.BusinessGroup ||
            node.type == CatalogNodeType.VirtualOrg ||
            node.type == CatalogNodeType.Device
        ) {
            sb.append("<Address>").append(escapeXmlText(f["Address"] ?: "")).append("</Address>\n")
        } else {
            sb.append("<Address>").append(escapeXmlText(f["Address"] ?: "Mobile")).append("</Address>\n")
        }
        sb.append("<Parental>").append(node.type.parental).append("</Parental>\n")
        sb.append("<ParentID>").append(escapeXmlText(parentId)).append("</ParentID>\n")
        sb.append("<SafetyWay>").append(escapeXmlText(f["SafetyWay"] ?: "0")).append("</SafetyWay>\n")
        sb.append("<RegisterWay>").append(escapeXmlText(f["RegisterWay"] ?: "1")).append("</RegisterWay>\n")
        sb.append("<Secrecy>").append(escapeXmlText(f["Secrecy"] ?: "0")).append("</Secrecy>\n")
        sb.append("<Status>").append(escapeXmlText(f["Status"] ?: "ON")).append("</Status>")
        sb.append("\n</Item>")
        return sb.toString()
    }
}

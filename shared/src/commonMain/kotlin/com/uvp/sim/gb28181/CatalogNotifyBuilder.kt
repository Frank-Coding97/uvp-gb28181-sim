package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.ChannelProfile
import com.uvp.sim.config.GbVersion

/**
 * 构造 GB/T 28181 Catalog NOTIFY body(MANSCDP+xml)。
 *
 * 跟 [CatalogResponse.buildFromTree] 共用同一套 Item 序列化逻辑,只是顶层 wrapper 不同:
 *   - 本对象的 [build]/[buildAll]/[buildIncremental] 产 `<Notify>`
 *   - [renderResponse]/[renderResponseAll] 产 `<Response>`
 *   - 节点列表来自动态目录树,不是单通道硬编码
 *   - 输出顺序:深度优先(父先于子),从根开始 DFS
 *
 * ## ⭐ Item 输出形态按 `version` 双分支(§9.3.1 / 附录 A)
 *
 * - **两版共有且位置相同**:`Item` 直接子级的 `IPAddress`/`Port` + `<Info>` 内的
 *   `PTZType`/`RoomType`/`SupplyLightType`/`DirectionType`/`Resolution`
 * - **V2016**:`<Info>` 内**多出** `PositionType`/`UseType`(这两个 2022 已删除),
 *   且 `BusinessGroupID` **仍在 `<Info>` 内**
 * - **V2022**:`<Info>` 内多出 `PhotoelectricImagingType`/`CapturePositionType`/`StreamNumberList`,
 *   且 `BusinessGroupID` **提到 `Item` 层**
 *
 * ⛔ 不允许「一条报文把两版字段都写进去」的超集做法 —— 严格 XSD 校验下**两版都不合规**
 * (2016 会拒 Item 层的 `BusinessGroupID` 与 2022 独有元素;2022 会拒 `PositionType`/`UseType`),
 * 而且同一字段双写会带来「哪个说了算」的歧义。版本是注册时协商出的**单一事实**,运行期不变。
 *
 * ## 字段映射
 *
 * - `Parental`:来自 `CatalogNodeType.parental`
 * - `ParentID`:根节点指向自身,其余指向 `parentId`
 * - **通道属性**:优先取 `CatalogNode.fields` 同名键(支持多通道各自属性),
 *   缺失回退 `channel` 参数;两者都无则该元素**不输出**(标准里这些全是 `minOccurs=0`)
 * - 其它字段从 `CatalogNode.fields` 取,缺失给保守默认
 */
object CatalogNotifyBuilder {

    /**
     * @param version **无默认值,必须显式传** —— 传有效国标版本(`min(本机声明, 平台声明)`),
     *   它决定 Item 的输出形态(见类 KDoc 的双分支说明)。刻意不给默认值,否则漏传会静默取
     *   一个固定版本,把「对面其实是 2016」这一路悄悄发错。
     * @param channel 设备级通道属性兜底。为 null 时只输出 `CatalogNode.fields` 里已有的键。
     */
    fun build(
        deviceId: String,
        sn: Int,
        tree: List<CatalogNode>,
        version: GbVersion,
        channel: ChannelProfile? = null,
    ): String = renderEnvelope(
        wrapperTag = "Notify",
        deviceId = deviceId,
        sn = sn.toString(),
        tree = tree,
        version = version,
        channel = channel,
    )

    /**
     * 附录 M 多响应分包。所有包共享同一个 SN 和全量 SumNum，DeviceList Num 是当前包条数。
     */
    fun buildAll(
        deviceId: String,
        sn: Int,
        tree: List<CatalogNode>,
        pageSize: Int,
        version: GbVersion,
        channel: ChannelProfile? = null,
    ): List<String> = renderEnvelopes(
        wrapperTag = "Notify",
        deviceId = deviceId,
        sn = sn.toString(),
        tree = tree,
        pageSize = pageSize,
        version = version,
        channel = channel,
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
        events: List<CatalogChangeEvent>,
        version: GbVersion,
        channel: ChannelProfile? = null,
    ): String = renderIncrementalEnvelope(deviceId, sn, events, events.size, version, channel)

    fun buildIncrementalAll(
        deviceId: String,
        sn: Int,
        events: List<CatalogChangeEvent>,
        pageSize: Int,
        version: GbVersion,
        channel: ChannelProfile? = null,
    ): List<String> {
        if (events.isEmpty()) {
            return listOf(renderIncrementalEnvelope(deviceId, sn, emptyList(), 0, version, channel))
        }
        return events.chunked(pageSize.coerceIn(1, 10_000)).map { page ->
            renderIncrementalEnvelope(deviceId, sn, page, events.size, version, channel)
        }
    }

    private fun renderIncrementalEnvelope(
        deviceId: String,
        sn: Int,
        events: List<CatalogChangeEvent>,
        sumNum: Int,
        version: GbVersion,
        channel: ChannelProfile?,
    ): String {
        val items = events.joinToString(separator = "\n") { renderEventItem(it, version, channel) }

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
            sb.append("<DeviceList Num=\"").append(events.size).append("\">\n")
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

    private fun renderEventItem(
        event: CatalogChangeEvent,
        version: GbVersion,
        channel: ChannelProfile?,
    ): String {
        return when (event) {
            is CatalogChangeEvent.Add -> renderItemWithEvent(event.node, "ADD", version, channel)
            is CatalogChangeEvent.Update -> renderItemWithEvent(event.node, "UPDATE", version, channel)
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

    private fun renderItemWithEvent(
        node: CatalogNode,
        eventTag: String,
        version: GbVersion,
        channel: ChannelProfile?,
    ): String {
        val full = renderItem(node, emptyList(), version, channel)
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
        tree: List<CatalogNode>,
        version: GbVersion,
        channel: ChannelProfile? = null,
    ): String = renderEnvelope(
        wrapperTag = "Response",
        deviceId = deviceId,
        sn = sn,
        tree = tree,
        version = version,
        channel = channel,
    )

    internal fun renderResponseAll(
        deviceId: String,
        sn: String,
        tree: List<CatalogNode>,
        pageSize: Int,
        version: GbVersion,
        channel: ChannelProfile? = null,
    ): List<String> = renderEnvelopes(
        wrapperTag = "Response",
        deviceId = deviceId,
        sn = sn,
        tree = tree,
        pageSize = pageSize,
        version = version,
        channel = channel,
    )

    private fun renderEnvelopes(
        wrapperTag: String,
        deviceId: String,
        sn: String,
        tree: List<CatalogNode>,
        pageSize: Int,
        version: GbVersion,
        channel: ChannelProfile?,
    ): List<String> {
        val ordered = orderDfs(tree)
        if (ordered.isEmpty()) {
            return listOf(renderOrderedEnvelope(wrapperTag, deviceId, sn, emptyList(), 0, version, channel))
        }
        val effectivePageSize = pageSize.coerceIn(1, 10_000)
        return ordered.chunked(effectivePageSize).map { page ->
            renderOrderedEnvelope(wrapperTag, deviceId, sn, page, ordered.size, version, channel)
        }
    }

    private fun renderEnvelope(
        wrapperTag: String,
        deviceId: String,
        sn: String,
        tree: List<CatalogNode>,
        version: GbVersion,
        channel: ChannelProfile?,
    ): String {
        val ordered = orderDfs(tree)
        return renderOrderedEnvelope(wrapperTag, deviceId, sn, ordered, ordered.size, version, channel)
    }

    private fun renderOrderedEnvelope(
        wrapperTag: String,
        deviceId: String,
        sn: String,
        ordered: List<CatalogNode>,
        sumNum: Int,
        version: GbVersion,
        channel: ChannelProfile?,
    ): String {
        val items = ordered.joinToString(separator = "\n") { renderItem(it, ordered, version, channel) }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<").append(wrapperTag).append(">\n")
        sb.append("<CmdType>Catalog</CmdType>\n")
        sb.append("<SN>").append(sn).append("</SN>\n")
        sb.append("<DeviceID>").append(escapeXmlText(deviceId)).append("</DeviceID>\n")
        sb.append("<SumNum>").append(sumNum).append("</SumNum>\n")
        if (ordered.isEmpty()) {
            // GB/T 28181-2022 附录 M:SumNum=0 时不携带目录列表。
        } else {
            sb.append("<DeviceList Num=\"").append(ordered.size).append("\">\n")
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

    private fun renderItem(
        node: CatalogNode,
        allNodes: List<CatalogNode>,
        version: GbVersion,
        channel: ChannelProfile?,
    ): String {
        val f = node.fields
        val parentId = if (node.parentId == node.id) node.id else node.parentId
        // BusinessGroupID 取值优先级:节点级显式字段 → 虚拟组织向上找业务分组祖先 → 通道属性
        // (三者都是"显式指定",不存在互相覆盖的歧义;都没有则整条省略)
        val businessGroupId = f["BusinessGroupID"]
            ?: findBusinessGroupId(node, allNodes)
            ?: if (node.type == CatalogNodeType.VideoChannel) {
                channel?.businessGroupId?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
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
        // 两版共有、同位置:2016 与 2022 的 IPAddress/Port 都在 Secrecy 之后(标准注释写"可选")。
        // "0.0.0.0" 是 ChannelProfile 的占位默认值,不是有效地址 → 跳过,等 Engine 注入真实 IP。
        val ipAddress = f["IPAddress"] ?: channel?.ipAddress
        if (!ipAddress.isNullOrEmpty() && ipAddress != "0.0.0.0") {
            sb.append("<IPAddress>").append(escapeXmlText(ipAddress)).append("</IPAddress>\n")
        }
        val port = f["Port"] ?: channel?.port?.toString()
        if (!port.isNullOrEmpty()) {
            sb.append("<Port>").append(escapeXmlText(port)).append("</Port>\n")
        }
        sb.append("<Status>").append(escapeXmlText(f["Status"] ?: "ON")).append("</Status>")
        // ⭐ 位置差异:2022 的 BusinessGroupID 在 Item 层(见类 KDoc);
        // 2016 的仍在 <Info> 内,由 buildInfoBlock 负责输出。
        if (version == GbVersion.V2022 && businessGroupId != null) {
            sb.append("\n<BusinessGroupID>").append(escapeXmlText(businessGroupId)).append("</BusinessGroupID>")
        }
        val info = buildInfoBlock(node, version, channel, businessGroupId)
        if (info.isNotEmpty()) {
            sb.append("\n").append(info)
        }
        sb.append("\n</Item>")
        return sb.toString()
    }

    /**
     * 构造 `<Info>` 容器(§9.3.1)。
     *
     * **只对摄像机类节点**([CatalogNodeType.VideoChannel])输出 —— 其它节点类型
     * (系统 / 业务分组 / 虚拟组织 / 行政区划 / 报警通道)一律不输出,因为标准里这一组字段
     * 都限定「当为摄像机时可选」。
     *
     * ⭐ 双版本分支:两版共有的 5 个字段无条件输出;2016 与 2022 各有一组独有字段,
     * 且 `BusinessGroupID` 的位置在两版不同。
     */
    private fun buildInfoBlock(
        node: CatalogNode,
        version: GbVersion,
        channel: ChannelProfile?,
        businessGroupId: String?,
    ): String {
        if (node.type != CatalogNodeType.VideoChannel) return ""
        val f = node.fields
        val inner = StringBuilder()

        // —— 两版共有、同名同层 ——
        // PTZType:0 是模拟器内部占位值,两版标准的合法值域都是 1 起 → 0 不输出
        val ptz = f["PTZType"] ?: channel?.ptzType?.gbCode?.takeIf { it != 0 }?.toString()
        appendInfoElement(inner, "PTZType", ptz)
        appendInfoElement(inner, "RoomType", f["RoomType"] ?: channel?.roomType?.gbCode?.toString())
        appendInfoElement(
            inner, "SupplyLightType",
            f["SupplyLightType"] ?: channel?.supplyLightType?.gbCode?.toString()
        )
        appendInfoElement(
            inner, "DirectionType",
            f["DirectionType"] ?: channel?.directionType?.gbCode?.toString()
        )
        appendInfoElement(inner, "Resolution", f["Resolution"] ?: channel?.resolution)

        if (version == GbVersion.V2016) {
            // 仅 2016 —— 这两个字段 2022 已删除
            appendInfoElement(
                inner, "PositionType",
                f["PositionType"] ?: channel?.positionType?.gbCode?.toString()
            )
            appendInfoElement(inner, "UseType", f["UseType"] ?: channel?.useType?.gbCode?.toString())
            // 2016 的 BusinessGroupID 留在 <Info> 内(2022 才提到 Item 层)
            appendInfoElement(inner, "BusinessGroupID", businessGroupId)
        } else {
            // 仅 2022 新增
            appendInfoElement(
                inner, "PhotoelectricImagingType",
                f["PhotoelectricImagingType"]
                    ?: channel?.photoelectricImagingTypes?.takeIf { it.isNotEmpty() }
                        ?.joinToString("/") { it.gbCode.toString() }
            )
            // 取值须符合附录 O(7 位层次码);本批不做值域展开,留待 B-5,此处只透传节点字段
            appendInfoElement(inner, "CapturePositionType", f["CapturePositionType"])
            appendInfoElement(inner, "StreamNumberList", f["StreamNumberList"] ?: channel?.streamNumberList)
        }

        // 一个字段都没算出来时**不输出空容器** —— 标准里 `<Info>` 是 `minOccurs="0"`,
        // 空 `<Info></Info>` 没有意义,还会被严格的 XSD 校验判成内容不符。
        if (inner.isEmpty()) return ""
        return StringBuilder().append("<Info>").append(inner).append("\n</Info>").toString()
    }

    /**
     * 追加一行 `<Tag>值</Tag>`;**值为空则整条省略** —— 标准里 `<Info>` 下这些元素
     * 全是 `minOccurs="0"`,宁可不发也不发空标签。
     */
    private fun appendInfoElement(sb: StringBuilder, tag: String, value: String?) {
        if (value.isNullOrEmpty()) return
        sb.append("\n<").append(tag).append(">").append(escapeXmlText(value))
            .append("</").append(tag).append(">")
    }

    private fun findBusinessGroupId(node: CatalogNode, allNodes: List<CatalogNode>): String? {
        if (node.type != CatalogNodeType.VirtualOrg) return null
        val byId = allNodes.associateBy { it.id }
        var parent = byId[node.parentId]
        val seen = mutableSetOf<String>()
        while (parent != null && seen.add(parent.id)) {
            if (parent.type == CatalogNodeType.BusinessGroup) return parent.id
            parent = byId[parent.parentId]
        }
        return null
    }
}

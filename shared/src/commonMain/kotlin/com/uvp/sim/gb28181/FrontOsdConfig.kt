package com.uvp.sim.gb28181

import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.SimConfig
import kotlinx.serialization.Serializable

/**
 * 前端 OSD 里的一条自由文本（`OSDCfgType/Item`）。
 *
 *   - [text] `Text`，文字内容，长度 0~32（必选）
 *   - [x]    `X`，文字 X 像素坐标（必选），原点 = 播放窗口左上角，水平向右为正
 *   - [y]    `Y`，文字 Y 像素坐标（必选），竖直向下为正
 */
@Serializable
data class OsdTextItem(val text: String = "", val x: Int = 0, val y: Int = 0)

/**
 * GB/T 28181-2022 **A.2.1.12 OSD 配置类型**（`OSDCfgType`）的当前取值。
 *
 * ⛔⛔ **这是「前端 OSD」= 设备烧进视频流的叠加，不是本机预览的界面叠层。**
 * 本仓 `config/OsdConfig.kt` 那套（时间戳 / 通道名 / 平铺水印 三层 + 5 个锚点 + 3 档字号 +
 * 自定义颜色）是**播放器预览里的本地叠加**，两者只是"看起来都在画字"：
 *
 * | | 标准 `OSDCfgType`（本文件） | 本仓 `config/OsdConfig`（预览叠层） |
 * |---|---|---|
 * | 坐标系 | **绝对像素**（左上角原点，向右/向下为正） | 5 个**锚点** |
 * | 条目 | 时间戳 1 条 + **最多 8 条自由文本** | 固定 3 层 |
 * | 尺寸 | `Length`/`Width` 像素框 | `OsdSize` 三档 |
 * | 颜色 | **没有颜色概念** | `fillColor` / `outlineColor` |
 * | 平铺水印 | **标准里没有这个概念** | `watermark` 层 |
 *
 * ⇒ 决策（本仓 2026-09-19 定）：**协议侧按标准像素模型独立记账（本文件），预览侧保留锚点式**。
 * 不做"把像素反查成最近锚点"的降级映射 —— 8 条自由文本各自的 X/Y 无法反查到 5 个锚点，
 * 那种映射会**静默丢信息**，且回读对账拿到的就不再是平台下发的值。
 * 两者只在**出厂默认**处建立一次单向换算（见 [FrontOsdConfig.defaultFor]），方向明确、可追溯。
 *
 * 字段与必选性：
 * ```
 * <OSDConfig>
 *   <Length>1080</Length>       <!-- 配置窗口长度像素值（必选） -->
 *   <Width>1920</Width>         <!-- 配置窗口宽度像素值（必选） -->
 *   <TimeX>10</TimeX>           <!-- 时间 X 像素坐标（必选） -->
 *   <TimeY>10</TimeY>           <!-- 时间 Y 像素坐标（必选） -->
 *   <TimeEnable>1</TimeEnable>  <!-- 显示时间开关（可选，XSD default="1"） -->
 *   <TimeType>0</TimeType>      <!-- 时间显示类型（可选，minOccurs=0）：0-YYYY-MM-DD HH:MM:SS / 1-YYYY年MM月DD日HH:MM:SS -->
 *   <TextEnable>1</TextEnable>  <!-- 显示文字开关（可选，XSD default="1"） -->
 *   <SumNum>1</SumNum>          <!-- 显示文字行数总数（必选） -->
 *   <Item><Text>通道1</Text><X>10</X><Y>34</Y></Item>   <!-- 最多 8 条 -->
 * </OSDConfig>
 * ```
 *
 * ⭐ [timeEnable] / [textEnable] 都是**确定的 0/1**，不留 `null`：XSD 的 `default="1"`
 * 语义是「元素缺席时取 1」，而设备侧"开关到底是开还是关"总有一个确定值 ——
 * 把缺席原样回带会让对端分不清"设备没报"与"设备报了个默认"。
 * 反过来 [timeType] 保留 `Int?`：标准没给它 default，**"设备不指定格式"是一个真实状态**，
 * 渲染时整个元素不出现（不是补 0 —— 0 表示「YYYY-MM-DD HH:MM:SS」这个**特定**格式）。
 *
 * ⭐ [sumNum] 不独立存，恒等于 `items.size`（同 [PictureMaskState.sumNum]）。
 */
@Serializable
data class FrontOsdState(
    /** `Length`：配置窗口长度像素值。本仓口径 = 视频**水平**像素数，见 [FrontOsdConfig.defaultFor]。 */
    val length: Int = 0,
    /** `Width`：配置窗口宽度像素值。本仓口径 = 视频**垂直**像素数。 */
    val width: Int = 0,
    val timeX: Int = 0,
    val timeY: Int = 0,
    /** `TimeEnable`，0/1。XSD default=1，缺席按 1 收（见类注释）。 */
    val timeEnable: Int = 1,
    /** `TimeType`，0/1。null = 设备不指定时间格式（元素缺席）。**不是 0**。 */
    val timeType: Int? = null,
    /** `TextEnable`，0/1。XSD default=1。 */
    val textEnable: Int = 1,
    /** 自由文本行，最多 8 条（标准 `Item maxOccurs="8"`）。 */
    val items: List<OsdTextItem> = emptyList(),
) {
    /** 线格式用：显示文字行数总数（= 实际条数）。 */
    val sumNum: Int get() = items.size
}

/**
 * `OSDConfig` 的取值表、出厂默认与线格式。
 *
 * ⛔ 与 [PictureMaskConfig] 同一口径：**结构违规整块拒绝**（不动状态 + warn）。
 */
object FrontOsdConfig {

    private const val OFF = 0
    private const val ON = 1

    /** 标准上限：`Item maxOccurs="8"`；`Text` 长度 0~32。 */
    const val MAX_ITEMS = 8
    const val MAX_TEXT_LENGTH = 32

    /** 时间戳默认落位（左上角内边距），与画布 OSD 的 10dp 内边距同源。 */
    const val DEFAULT_TIME_X = 10
    const val DEFAULT_TIME_Y = 10

    /** 通道名默认落位：时间戳**下面一行**。 */
    const val DEFAULT_TEXT_X = 10
    const val DEFAULT_TEXT_Y = 34

    /**
     * 出厂默认 —— **从本机三层 OSD（[SimConfig.osd]）单向换算而来**，这是本仓口径，记全：
     *
     * | 本机 `config.osd` 层 | 映射到标准字段 | 说明 |
     * |---|---|---|
     * | `timestamp.enabled` | `TimeEnable` | 画布上真的在显示时间戳，回读报 0 就是编答案 |
     * | `timestamp` 的位置 | `TimeX` / `TimeY` | 锚点式 → 协议只有绝对像素，取 [DEFAULT_TIME_X]/[DEFAULT_TIME_Y]（左上，与画布内边距一致） |
     * | `channelName.enabled` | `TextEnable` | 同上 |
     * | `channelName.text` | `Item[0].Text` | 文本为空则**不发这条 Item**（`SumNum=0`），但 `TextEnable` 保持 1 |
     * | `channelName` 的位置 | `Item[0].X/Y` | 取 [DEFAULT_TEXT_X]/[DEFAULT_TEXT_Y]（时间戳下一行） |
     * | `watermark` | **无对应，丢弃** | 标准里没有"平铺水印"概念，硬塞进 `Item` 会造出一条语义不明的文本 |
     *
     * ⚠️ `Length`/`Width` 的**轴对应是标准没写死的**（只写"长度/宽度像素值"）。
     * 本仓口径：`Length` = 视频**水平**像素数、`Width` = **垂直**像素数（中文"长/宽"按长边在前）。
     * **平台侧必须与这里一致** —— 若将来按相反口径实现，改这一处即可（唯一换算点）。
     * 窗口尺寸取当前生效分辨率（[SimConfig.video]），因为"配置窗口"就是这一路视频的画面。
     */
    fun defaultFor(config: SimConfig): FrontOsdState {
        val osd = config.osd
        val channelText = osd.channelName.text.trim().ifEmpty {
            // 通道名层开着但没填文字时，退回设备名 —— 与目录/预览里显示的通道名同源。
            config.device.channelNameForChannel(config.device.videoChannelId).trim()
        }
        val items = buildList {
            if (osd.channelName.enabled && channelText.isNotEmpty()) {
                add(OsdTextItem(text = channelText.take(MAX_TEXT_LENGTH), x = DEFAULT_TEXT_X, y = DEFAULT_TEXT_Y))
            }
        }
        return FrontOsdState(
            length = config.video.resolution.widthPx,
            width = config.video.resolution.heightPx,
            timeX = DEFAULT_TIME_X,
            timeY = DEFAULT_TIME_Y,
            timeEnable = if (osd.timestamp.enabled) ON else OFF,
            // 本机没有"时间格式"这一维，标准也只定义两种，缺席（null）是最诚实的值。
            timeType = null,
            textEnable = if (osd.channelName.enabled) ON else OFF,
            items = items,
        )
    }

    /**
     * 「前端 OSD」的**生效取值** —— 平台下发过就用平台的，否则用出厂派生。
     *
     * ⭐ 这是**界面回显**的唯一入口：设置页的国标区块、以及任何要回答"设备当前前端 OSD 是什么"
     * 的地方都走它。⛔ 不要各处自己写一遍 `frontOsd ?: defaultFor(config)` ——
     * 两处各判一次，改一处就有一处还是老行为，而两边**都显示得出东西**，最难发现。
     */
    fun effective(config: SimConfig, frontOsd: FrontOsdState?): FrontOsdState =
        frontOsd ?: defaultFor(config)

    /**
     * **反向投影**：国标 `OSDConfig` → 本机三层 OSD（[OsdConfig]）。
     *
     * ## 为什么需要它
     * 真机上「前端 OSD」是**一份配置、两个入口**（摄像头自己的界面 + 平台 `OSDConfig`，
     * 改哪边另一边刷新就能看到）。本仓用两份结构分别模拟"本机预览叠加"与"协议面"，
     * 所以平台配了 OSD 之后要让**本机预览也跟着变** —— 否则平台配完设备屏幕上一点动静都没有，
     * 演示时看起来就是"没生效"。
     *
     * ## 映射（**槽位必须与 [defaultFor] 的正向映射逐一对应，改一处就要改另一处**）
     * | 国标字段 | → 本机 | 说明 |
     * |---|---|---|
     * | `TimeEnable` | `timestamp.enabled` | |
     * | `TimeX`/`TimeY` | `timestamp.position` | 绝对像素 → [nearestAnchor] 取最近锚点（**有损**） |
     * | `TextEnable` + `Item[0]` | `channelName` 的 `text`/`position`/`enabled` | 与正向 `channelName → Item[0]` 对齐 |
     * | `TextEnable` + `Item[1]` | `watermark` 的 `text`/`position`/`enabled` | 与正向 `watermark → Item[1]` 对齐 |
     * | `Item[2..7]` | **无对应** | 本机预览只有 2 个文字层。**数据不丢** —— 仍在 [FrontOsdState.items] 里，回读照发 |
     * | `Length`/`Width` | 不投影 | 它是协议坐标系尺寸，本机锚点模型用不上 |
     *
     * ## 明确**不覆盖**的维度
     * 本机有而国标没有的字段一律取 [base] 原值：**字号 `size`、颜色 `fillColor`/`outlineColor`**，
     * 以及水印的平铺语义。⛔ 别顺手给它们编一个"默认值" —— 标准里没有这一维，
     * 编出来的值会盖掉用户在设置页里的选择，而且没有任何协议依据。
     *
     * ## 已知有损点（用它就要接受）
     * 1. 绝对像素坐标 → 5 个锚点：只能取最近的那个，**原始坐标丢失**；
     * 2. 第 3~8 条文字**在本机预览里不显示**（数据仍在）；
     * 3. 平台把两个条目配到同一锚点时，本机叠层的两行字会落在同一处。
     *
     * ⚠️ 正因如此，**回读永远走 [FrontOsdState] 的原始值**（`frontOsd ?: defaultFor`），
     * 绝不从投影后的 [OsdConfig] 反推回去 —— 那会把上面三处损失带进回读值，
     * 平台就会读到一份"自己没发过"的配置。
     */
    fun toLocalOsd(state: FrontOsdState, base: OsdConfig): OsdConfig {
        val anchorOf = { x: Int, y: Int -> nearestAnchor(x, y, state.length, state.width) }
        val item0 = state.items.getOrNull(0)
        val item1 = state.items.getOrNull(1)
        val textOn = state.textEnable == ON
        return OsdConfig(
            timestamp = base.timestamp.copy(
                enabled = state.timeEnable == ON,
                position = anchorOf(state.timeX, state.timeY),
            ),
            channelName = base.channelName.copy(
                // `TextEnable=1` 却一条 Item 都没有 = "开着但没内容" → 置 false，
                // 否则预览会继续显示上一轮的旧文字，让人以为平台配的就是它。
                enabled = textOn && item0 != null,
                text = item0?.text ?: base.channelName.text,
                position = item0?.let { anchorOf(it.x, it.y) } ?: base.channelName.position,
            ),
            watermark = base.watermark.copy(
                enabled = textOn && item1 != null,
                text = item1?.text ?: base.watermark.text,
                position = item1?.let { anchorOf(it.x, it.y) } ?: base.watermark.position,
            ),
        )
    }

    /**
     * 绝对像素坐标 → 本机 5 个锚点里**最近的一个**（归一化后比欧氏距离）。
     *
     * 5 个锚点是四角 + 居中，**不是完整的 3×3 网格**（没有"上中/下中/中左/中右"），
     * 所以"按行列阈值各切三档"的写法在中性区间会给出武断结果。这里改成距各锚点理想位置
     * 的欧氏距离取最近 —— 规则**确定且可解释**，对"偏左上但不够靠边"这类坐标也答得自然。
     * 距离并列时取枚举里靠前的那个（[minBy] 的既定语义），结果稳定。
     *
     * 归一化用 `state` 自己声明的窗口尺寸（`Length`/`Width`）而不是本机分辨率：
     * 那是平台填坐标时所依据的坐标系，换别的尺寸会在平台填了不同窗口时整体偏移。
     */
    fun nearestAnchor(x: Int, y: Int, frameWidth: Int, frameHeight: Int): OsdPosition {
        val fx = x.toDouble() / frameWidth.coerceAtLeast(1).toDouble()
        val fy = y.toDouble() / frameHeight.coerceAtLeast(1).toDouble()
        return OsdPosition.entries.minBy { position ->
            val (ax, ay) = position.anchorFraction
            (fx - ax) * (fx - ax) + (fy - ay) * (fy - ay)
        }
    }

    /** 各锚点在画面里的理想相对位置（0~1）。 */
    private val OsdPosition.anchorFraction: Pair<Double, Double>
        get() = when (this) {
            OsdPosition.TOP_LEFT -> 0.0 to 0.0
            OsdPosition.TOP_RIGHT -> 1.0 to 0.0
            OsdPosition.BOTTOM_LEFT -> 0.0 to 1.0
            OsdPosition.BOTTOM_RIGHT -> 1.0 to 1.0
            OsdPosition.CENTER -> 0.5 to 0.5
        }

    /** 解析下发报文。 */
    fun parse(xml: String): ConfigParse<FrontOsdState> {
        val body = configBlockBody(xml, DeviceConfigBlock.OsdConfig.configType)
            ?: return absentOrEmptyBlock(xml, DeviceConfigBlock.OsdConfig.configType)

        // 4 个必选坐标 / 尺寸字段。缺任一即整块拒绝 —— 坐标不全的 OSD 配置没有可用含义。
        val length = childInt(body, "Length") ?: return ConfigParse.Rejected("缺必选字段 Length")
        val width = childInt(body, "Width") ?: return ConfigParse.Rejected("缺必选字段 Width")
        val timeX = childInt(body, "TimeX") ?: return ConfigParse.Rejected("缺必选字段 TimeX")
        val timeY = childInt(body, "TimeY") ?: return ConfigParse.Rejected("缺必选字段 TimeY")
        if (length <= 0 || width <= 0) {
            return ConfigParse.Rejected("Length/Width 必须为正（Length=$length Width=$width）")
        }
        if (timeX < 0 || timeY < 0) {
            return ConfigParse.Rejected("TimeX/TimeY 不得为负（TimeX=$timeX TimeY=$timeY）")
        }

        // 两个开关：XSD default="1" ⇒ 元素缺席取 1；在场则必须是 0/1。
        val timeEnable = switchOrDefault(body, "TimeEnable")
            ?: return ConfigParse.Rejected("TimeEnable 非 0/1")
        val textEnable = switchOrDefault(body, "TextEnable")
            ?: return ConfigParse.Rejected("TextEnable 非 0/1")

        // TimeType：minOccurs=0 且无 default ⇒ 缺席保持 null（"不指定格式"是真实状态）。
        val timeType = childInt(body, "TimeType")
        if (ManscdpParser.tagValue(body, "TimeType") != null && (timeType == null || timeType !in OFF..ON)) {
            return ConfigParse.Rejected("TimeType 非 0/1（只能 0 或 1，或缺席）")
        }

        childInt(body, "SumNum") ?: return ConfigParse.Rejected("缺必选字段 SumNum")

        val items = splitItems(body)
        if (items.size > MAX_ITEMS) {
            return ConfigParse.Rejected("文本行数 ${items.size} 超过标准上限 $MAX_ITEMS")
        }
        val parsed = mutableListOf<OsdTextItem>()
        for ((index, item) in items.withIndex()) {
            val text = ManscdpParser.tagValue(item, "Text")
                ?: return ConfigParse.Rejected("第 ${index + 1} 条 Item 缺必选字段 Text")
            if (text.length > MAX_TEXT_LENGTH) {
                return ConfigParse.Rejected(
                    "第 ${index + 1} 条 Item 的 Text 长度 ${text.length} 超过标准上限 $MAX_TEXT_LENGTH"
                )
            }
            val x = childInt(item, "X") ?: return ConfigParse.Rejected("第 ${index + 1} 条 Item 缺必选字段 X")
            val y = childInt(item, "Y") ?: return ConfigParse.Rejected("第 ${index + 1} 条 Item 缺必选字段 Y")
            if (x < 0 || y < 0) {
                return ConfigParse.Rejected("第 ${index + 1} 条 Item 的坐标不得为负（X=$x Y=$y）")
            }
            parsed += OsdTextItem(text = text, x = x, y = y)
        }

        return ConfigParse.Accepted(
            FrontOsdState(
                length = length,
                width = width,
                timeX = timeX,
                timeY = timeY,
                timeEnable = timeEnable,
                timeType = timeType,
                textEnable = textEnable,
                items = parsed,
            )
        )
    }

    /** 0/1 开关，缺席取 XSD 的 `default="1"`；在场但不是 0/1 返回 null（= 非法）。 */
    private fun switchOrDefault(body: String, tag: String): Int? {
        val raw = ManscdpParser.tagValue(body, tag)?.trim()
        if (raw.isNullOrEmpty()) return ON
        val v = raw.toIntOrNull() ?: return null
        return if (v == OFF || v == ON) v else null
    }

    /** 切出顶层 `<Item>…</Item>` 列表（OSD 的 `Item` 无嵌套同名子元素）。 */
    private fun splitItems(body: String): List<String> {
        val items = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val open = body.indexOf("<Item", cursor)
            if (open < 0) break
            val openEnd = body.indexOf('>', open)
            if (openEnd < 0) break
            val close = body.indexOf("</Item>", openEnd)
            if (body[openEnd - 1] == '/' || close < 0) {
                cursor = openEnd + 1
                continue
            }
            items += body.substring(openEnd + 1, close)
            cursor = close + "</Item>".length
        }
        return items
    }

    /**
     * 回读应答块。
     *
     * ⛔ 元素顺序照 XSD：`Length` → `Width` → `TimeX` → `TimeY` → `TimeEnable` → `TimeType`
     * → `TextEnable` → `SumNum` → `Item*`。
     * ⛔ `SumNum` 取实到条数；`TimeType` 为 null 时**整个元素不出现**。
     */
    fun render(state: FrontOsdState): String = buildString {
        append("<OSDConfig>\n")
        append("<Length>").append(state.length).append("</Length>\n")
        append("<Width>").append(state.width).append("</Width>\n")
        append("<TimeX>").append(state.timeX).append("</TimeX>\n")
        append("<TimeY>").append(state.timeY).append("</TimeY>\n")
        append("<TimeEnable>").append(state.timeEnable).append("</TimeEnable>\n")
        state.timeType?.let { append("<TimeType>").append(it).append("</TimeType>\n") }
        append("<TextEnable>").append(state.textEnable).append("</TextEnable>\n")
        append("<SumNum>").append(state.sumNum).append("</SumNum>\n")
        for (item in state.items) {
            append("<Item><Text>").append(item.text).append("</Text>")
            append("<X>").append(item.x).append("</X>")
            append("<Y>").append(item.y).append("</Y></Item>\n")
        }
        append("</OSDConfig>\n")
    }
}

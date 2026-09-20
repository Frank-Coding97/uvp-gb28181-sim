package com.uvp.sim.gb28181

import kotlinx.serialization.Serializable

/**
 * 一个遮挡区域（`pictureMaskCfgType` 的 `RegionList/Item`）。
 *
 * ⛔ **`Point` 不是常见的 `x,y,w,h`，而是「左上角 + 右下角」两个角点**：
 * 标准原文「区域左上角、右下角坐标（lx,ly,rx,ry，单位像素），格式如"20,30,50,60"」。
 * 本仓前端 `deviceConfigGroups.ts` 用的是 `[x,y,w,h]` —— 那是照海康 ISP 口径编的，
 * 接后端之前必须先按本条改正，否则会画出**偏移一整个宽高**的假遮挡。
 *
 * 拆成 4 个 Int 而不是留原始串：回读时统一按 `lx,ly,rx,ry` 规范化输出，
 * 免得平台写 `20, 30, 50, 60`（带空格）就原样回带空格 —— 对端再解析一次就崩。
 *
 * ⚠️ **所有字段都给默认值**（哪怕 wire 上是必选）：本类型会进设备状态存档，
 * 而 kotlinx.serialization 对**没有默认值**的字段是"JSON 里缺了就抛异常"——
 * 将来给这个类加一个字段，旧存档就会**整份解不出来**（本仓 09-18 为枚举改名踩过，
 * 那次是整份 SIP 配置退回默认模板）。带默认值 = 新字段对旧存档优雅降级。
 * 代价：可以构造出一个 `PictureMaskRegion()`，因此**校验只认 [PictureMaskConfig.parse]**。
 */
@Serializable
data class PictureMaskRegion(
    /** `Seq`，区域编号，取值范围 1~4（必选）。 */
    val seq: Int = 0,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    /** 线格式：`左x,左y,右x,右y`，逗号分隔、无空格。 */
    val pointLiteral: String get() = "$left,$top,$right,$bottom"

    /** 人读串，只给 UI / 日志。 */
    val displayLabel: String get() = "$left,$top → $right,$bottom"

    /**
     * 这一条在画面上是否真的占了面积（`右>左 && 下>上`）。
     *
     * ⭐ 零面积**不是"一条看不见的区域"，而是"删除这一槽"的写法** —— 真机口径见
     * [PictureMaskConfig.parse]。
     */
    val hasArea: Boolean get() = right > left && bottom > top
}

/**
 * GB/T 28181-2022 **A.2.1.17 视频画面遮挡配置类型**（`pictureMaskCfgType`）的当前取值。
 *
 * 结构（含必选性，抄自原文）：
 * ```xml
 * <PictureMask>
 *   <On>1</On>                       <!-- 遮挡开关，0-关闭 / 1-打开（必选） -->
 *   <SumNum>2</SumNum>               <!-- 区域总数（必选） -->
 *   <RegionList Num="2">             <!-- 区域列表（可选）；Num = 当前区域个数，无区域时取值 0 -->
 *     <Item><Seq>1</Seq><Point>20,30,50,60</Point></Item>
 *     <!-- Item 最多 4 个 -->
 *   </RegionList>
 * </PictureMask>
 * ```
 *
 * ⭐ [sumNum] **不独立存**：它必须恒等于 `regions.size`。存成两个字段就一定会出现
 * 「计数说 3 个、实际 2 个」的自相矛盾状态，而回读时对端只能信一个。
 * 渲染时由 `regions.size` 现算 —— 同 `VideoParamAttribute` 的 `Num` 属性口径。
 */
@Serializable
data class PictureMaskState(
    /** `On`，0/1。默认 0 = 不启用遮挡（设备出厂没有遮挡）。 */
    val on: Int = 0,
    /** 区域列表，按 [PictureMaskRegion.seq] 升序。最多 4 个（标准 `maxOccurs="4"`）。 */
    val regions: List<PictureMaskRegion> = emptyList(),
) {
    /** 线格式用：区域总数（= 实际条数，不单独存）。 */
    val sumNum: Int get() = regions.size
}

/**
 * `PictureMask` 的取值表、出厂默认与线格式。
 *
 * ⛔ **任何结构违规一律整块拒绝**（不动状态 + 记 warn），不做"跳过坏的那一条"。
 *
 * 这里与 `VideoParamAttribute.parseItems` 的"逐条跳过"**口径不同，是有意的**：
 * 那边的 `Item` 各自带 `StreamNumber`，"这条认不出来"只影响它自己那一路码流；
 * 而遮挡区域是一个**整体配置**（开关 + 区域集），落下半份的后果是"当前生效的遮挡"
 * 既不是下发前的也不是下发后的 —— 现场看到"改了遮挡、有的生效有的没生效"，
 * 而报文早已从 SIP trace 里过期了。宁可整块不收（回读保留原值 → 平台判 mismatch）。
 */
object PictureMaskConfig {

    private const val OFF = 0
    private const val ON = 1

    /** 标准 `Item maxOccurs="4"`；`Seq` 的取值范围也是 1~4。 */
    const val MAX_REGIONS = 4
    const val MIN_SEQ = 1
    const val MAX_SEQ = 4

    /** 出厂默认 = 关闭 + 无区域（设备出厂没有遮挡）。 */
    fun defaultFor(@Suppress("UNUSED_PARAMETER") config: com.uvp.sim.config.SimConfig) =
        PictureMaskState(on = OFF, regions = emptyList())

    /**
     * 解析下发报文。所有拒绝理由都带**具体是哪一条**（条款里最多只有 4 条，说得清）。
     */
    fun parse(xml: String): ConfigParse<PictureMaskState> {
        val body = configBlockBody(xml, DeviceConfigBlock.PictureMask.configType)
            ?: return absentOrEmptyBlock(xml, DeviceConfigBlock.PictureMask.configType)

        val on = childInt(body, "On") ?: return ConfigParse.Rejected("缺必选字段 On")
        if (on != OFF && on != ON) {
            return ConfigParse.Rejected("On=$on 非法（只能 $OFF/$ON）")
        }
        // SumNum 是必选，但**不作为落库依据**（真值 = 实到条数）。这里只检查它在不在，
        // 缺席说明平台报文结构不对，整块拒绝比"用实到条数悄悄兜过"更早暴露问题。
        childInt(body, "SumNum") ?: return ConfigParse.Rejected("缺必选字段 SumNum")

        val regionBody = configBlockBody(body, "RegionList")
            // RegionList 是可选元素：缺席 = 一个区域都没有（合法）。
            ?: return ConfigParse.Accepted(PictureMaskState(on = on, regions = emptyList()))

        val items = splitItems(regionBody)
        if (items.size > MAX_REGIONS) {
            return ConfigParse.Rejected("区域数 ${items.size} 超过标准上限 $MAX_REGIONS")
        }
        val regions = mutableListOf<PictureMaskRegion>()
        for ((index, item) in items.withIndex()) {
            val seq = childInt(item, "Seq") ?: return ConfigParse.Rejected("第 ${index + 1} 个 Item 缺必选字段 Seq")
            if (seq !in MIN_SEQ..MAX_SEQ) {
                return ConfigParse.Rejected("第 ${index + 1} 个 Item 的 Seq=$seq 越界（合法 $MIN_SEQ~$MAX_SEQ）")
            }
            val point = ManscdpParser.tagValue(item, "Point")?.trim()
                ?: return ConfigParse.Rejected("第 ${index + 1} 个 Item 缺必选字段 Point")
            val region = parsePoint(seq, point)
                ?: return ConfigParse.Rejected("第 ${index + 1} 个 Item 的 Point `$point` 非法（须为 `左x,左y,右x,右y`，且右下角不小于左上角）")
            // ⭐ 零面积（`0,0,0,0` 之类）= **删除这一槽**，不是"一条看不见的区域"。
            //    真机实证（2026-09-20，海康 DS-2DC2C040MY-DE）：平台为"被删掉的槽位"显式发
            //    `<Point>0,0,0,0</Point>`，设备执行后**该条消失、且不回显**（发 3 真 + 1 零 ⇒
            //    回读 `SumNum=3`）。本仓照真机口径收：收下就会多出一条 0×0 的幽灵区域
            //    （回读 `Num` 跟着变大、UI 列出一个 0×0 的行），平台侧"删掉一条区域"就变成
            //    "多出一条看不见的区域"。
            //    ⛔ 放在重复编号检查**之前**：删掉的槽位与真实槽位并存不该算"编号重复"。
            if (!region.hasArea) continue
            if (regions.any { it.seq == seq }) {
                return ConfigParse.Rejected("区域编号 $seq 重复")
            }
            regions += region
        }
        return ConfigParse.Accepted(
            PictureMaskState(on = on, regions = regions.sortedBy { it.seq })
        )
    }

    /**
     * 解析 `Point`。返回 null = 非法。
     *
     * 要求：恰好 4 段整数、都是非负、且 `左x ≤ 右x` 且 `左y ≤ 右y`。
     * 倒置矩形按非法处理 —— 标准明写"左上角、右下角"，倒置的矩形没有定义的含义，
     * 收下来只会让遮挡层画出不可预期的形状（或被渲染层静默丢掉，变成"配了没效果"）。
     */
    private fun parsePoint(seq: Int, literal: String): PictureMaskRegion? {
        val parts = literal.split(',').map { it.trim() }
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it < 0 }) return null
        val (lx, ly, rx, ry) = nums
        if (lx > rx || ly > ry) return null
        return PictureMaskRegion(seq = seq, left = lx, top = ly, right = rx, bottom = ry)
    }

    /** 切出 `<Item>…</Item>` 列表（`Item` 无属性，`Seq`/`Point` 都是直接子元素）。 */
    private fun splitItems(regionBody: String): List<String> {
        val items = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val open = regionBody.indexOf("<Item", cursor)
            if (open < 0) break
            val openEnd = regionBody.indexOf('>', open)
            if (openEnd < 0) break
            val close = regionBody.indexOf("</Item>", openEnd)
            if (regionBody[openEnd - 1] == '/' || close < 0) {
                // 自闭或结构坏了：跳过这一处，继续找后面（结构问题由条数与字段校验兜住）。
                cursor = openEnd + 1
                continue
            }
            items += regionBody.substring(openEnd + 1, close)
            cursor = close + "</Item>".length
        }
        return items
    }

    /**
     * 回读应答块。
     *
     * ⛔ `Num` 是 `RegionList` 的**属性**（不是子元素）。写成 `<Num>` 平台侧的 `xml:"Num,attr"`
     * 解不出来且不报错 —— 与 `VideoParamAttribute` 同一个坑。
     * ⛔ `SumNum` / `Num` 都取**实到条数**，保证与 `Item` 条数自洽。
     */
    fun render(state: PictureMaskState): String = buildString {
        append("<PictureMask>\n")
        append("<On>").append(state.on).append("</On>\n")
        append("<SumNum>").append(state.sumNum).append("</SumNum>\n")
        append("<RegionList Num=\"").append(state.sumNum).append("\">\n")
        for (region in state.regions) {
            append("<Item><Seq>").append(region.seq).append("</Seq>")
            append("<Point>").append(region.pointLiteral).append("</Point></Item>\n")
        }
        append("</RegionList>\n")
        append("</PictureMask>\n")
    }
}

package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion

/**
 * 模拟器**实现了**的 GB/T 28181 设备配置类型清单 —— 本仓关于「这一族到底有哪些类型」的**唯一真源**。
 *
 * 为什么要有这张表(而不是把类型名字符串散在 builder / dispatcher / 日志里)：
 *
 *  1. **回读侧要能回答「设备为什么没回这一块」**。平台拿到「应答里没有该块」时只有两个可能：
 *     ① 设备压根没实现这个 `ConfigType`;② 实现了但**有效版本**不是 2022(该类型 2022 才引入)。
 *     两者在报文上都表现为 `type_absent`,现场只能靠日志分辨 —— 所以「实现了哪些 + 各是哪一版
 *     引入的」必须是可查询的数据,不能是几处各写一遍的 `if`。
 *  2. **条款号要能随手取**。改这一族时经常要回答「这对应标准哪一条」,写死字符串迟早写错
 *     (本仓历史上已有虚条款号的先例)。
 *
 * ⚠️ **证据出处**：GB/T 28181-2022 附录 A（`.workbuddy/ocr/appA.txt`）
 *   - 可查询类型清单 = **A.2.4.7 设备配置查询请求**的正文说明（标准页 90），共 12 种
 *   - 下发命令元素 = **A.2.3.2.2 ~ A.2.3.2.13**
 *   - 回读应答元素 = **A.2.6.9 设备配置查询应答**（标准页 101-102）
 * 2016 版的可查询类型**只有 4 种**（BasicParam / VideoParamOpt / SVACEncodeConfig /
 * SVACDecodeConfig，见 `appA2016.txt` A.2.4.7）。
 *
 * ⛔⛔ **`configType` 与 `responseElement` 不一定同名** —— 抓拍配置就是反例：
 *   - 下发命令的元素名 = `SnapShotConfig`（A.2.3.2.12）
 *   - 查询请求里 `ConfigType` 的取值 = `SnapShotConfig`（A.2.4.7 清单）
 *   - 但**回读应答的元素名是 `SnapShot`**（A.2.6.9，`type="tg:snapshotCfgType"`）
 *   写成 `SnapShotConfig` 对端解不出来、**且不报错**（与 `VideoParamAttribute` 的 `Num`
 *   是属性不是子元素属同一类坑：报文看着正常，平台侧只是"有应答无数据"）。
 *   本枚举把这两个名字分开放,就是为了让这个差异只存在一处、能被 `renderBlock` 享用。
 */
enum class DeviceConfigBlock(
    /** 平台请求/下发里 `ConfigType` 元素（或下发块元素名）的取值。 */
    val configType: String,
    /** **回读应答**里的元素名。多数与 [configType] 相同，抓拍配置是例外。 */
    val responseElement: String,
    /** 标准条款号，形如 `A.2.1.17 / A.2.3.2.8`（类型定义 / 命令定义）。 */
    val clause: String,
    /** 该类型**引入的版本**。有效版本低于它时不回该块（见 [emits]）。 */
    val since: GbVersion,
) {
    /** A.2.1.19/A.2.3.2.2 —— 4 字段，2016 就有。 */
    BasicParam("BasicParam", "BasicParam", "A.2.1.19 / A.2.3.2.2", GbVersion.V2016),

    /** A.2.1.20 —— 只报「支持的范围」，2016 就有；2022 只在**查询**清单里，不在下发命令清单里。 */
    VideoParamOpt("VideoParamOpt", "VideoParamOpt", "A.2.1.20", GbVersion.V2016),

    /** A.2.1.13/A.2.3.2.5 —— 当前生效的视频参数（逐码流）。 */
    VideoParamAttribute("VideoParamAttribute", "VideoParamAttribute", "A.2.1.13 / A.2.3.2.5", GbVersion.V2022),

    /** A.2.1.15/A.2.3.2.6 —— 录像计划（7 天 × 每天 8 时段）。 */
    VideoRecordPlan("VideoRecordPlan", "VideoRecordPlan", "A.2.1.15 / A.2.3.2.6", GbVersion.V2022),

    /** A.2.1.16/A.2.3.2.7 —— 报警录像。 */
    VideoAlarmRecord("VideoAlarmRecord", "VideoAlarmRecord", "A.2.1.16 / A.2.3.2.7", GbVersion.V2022),

    /** A.2.1.17/A.2.3.2.8 —— 视频画面遮挡（≤4 个矩形）。 */
    PictureMask("PictureMask", "PictureMask", "A.2.1.17 / A.2.3.2.8", GbVersion.V2022),

    /** A.2.1.23/A.2.3.2.9 —— 画面翻转。**simpleType**（元素体就是一个整数，没有子元素）。 */
    FrameMirror("FrameMirror", "FrameMirror", "A.2.1.23 / A.2.3.2.9", GbVersion.V2022),

    /** A.2.1.18/A.2.3.2.10 —— 报警上报开关（移动侦测 / 区域入侵）。 */
    AlarmReport("AlarmReport", "AlarmReport", "A.2.1.18 / A.2.3.2.10", GbVersion.V2022),

    /** A.2.1.12/A.2.3.2.11 —— 前端 OSD 配置。 */
    OsdConfig("OSDConfig", "OSDConfig", "A.2.1.12 / A.2.3.2.11", GbVersion.V2022),

    /** A.2.1.24/A.2.3.2.12 —— 图像抓拍配置。⛔ 回读元素名是 `SnapShot`，见类注释。 */
    SnapShot("SnapShotConfig", "SnapShot", "A.2.1.24 / A.2.3.2.12", GbVersion.V2022),
    ;

    /** 本块在 [clause] 之外还要不要按国标版本门禁。 */
    val isGb2016: Boolean get() = since == GbVersion.V2016

    companion object {

        /**
         * 平台点名要了、但**模拟器没实现**的类型（`SVACEncodeConfig` / `SVACDecodeConfig`）。
         *
         * ⛔ 这两个**刻意不实现**：SVAC 是国标自有编码,模拟器没有对应的编解码实现,
         * 报一份假的 SVAC 配置比报「没有这一块」更有害。把它们列出来只有一个用途 ——
         * 应答日志里能明确说「这几种是设备不支持,不是你版本选错了」。
         */
        val NOT_IMPLEMENTED: Set<String> = setOf("SVACEncodeConfig", "SVACDecodeConfig")

        /** `ConfigType` 取值 → 块定义（大小写不敏感，标准里大小写是固定的，这里只做容错）。 */
        fun byConfigType(value: String): DeviceConfigBlock? =
            entries.firstOrNull { it.configType.equals(value.trim(), ignoreCase = true) }
    }
}

/**
 * 本次回读**会不会真的**带上 [block] —— 平台点名要了 **且** 有效版本 ≥ 该块引入版本。
 *
 * ⛔ 版本门禁只关**设备主动声明自己支持什么**这一半。平台**下发**那一半
 * （`<Control><CmdType>DeviceConfig</CmdType><PictureMask>…`）**绝不按版本拦** ——
 * 被误登记成 2016 的真 2022 设备必须还有一次"试一下"的机会，真相由回读对账暴露。
 * 两侧口径互为对照：`DeviceControlDispatcher.dispatch` 的对应分支没有任何版本判断。
 *
 * @param gbVersion **有效**国标版本（附录 I 协商结果 = `min(本机声明, 平台声明)`），
 *   不是 `config.gbVersion`。
 */
fun emits(block: DeviceConfigBlock, requested: List<String>, gbVersion: GbVersion): Boolean =
    gbVersion >= block.since && requested.any { it.equals(block.configType, ignoreCase = true) }

/**
 * 定位 `<Tag …>` / `<Tag/>` 的**开标签结束位置**（即 `>` 的下标）。从 [from] 开始找。
 * 找不到返回 null。
 *
 * ⛔ `indexOf("<$tag")` 单独用会误命中**同前缀**的另一个标签 —— 本族里就有两处真踩点：
 *   - `SnapShot` 是 `SnapShotConfig` 的前缀（回读块名 vs 下发块名）
 *   - `RecordSchedule` 是 `RecordScheduleSumNum` 的前缀（列表 vs 计数）
 * 后者会让列表扫描的**深度配平错位**，症状是"元素明明在、解析出来是空列表"。
 * 所以这里要求 `Tag` 之后紧跟 `>`、`/` 或空白，否则从下一个位置继续找。
 */
internal fun openTagEnd(xml: String, tag: String, from: Int = 0): Int? {
    var cursor = from
    while (true) {
        val open = xml.indexOf("<$tag", cursor)
        if (open < 0) return null
        val openEnd = xml.indexOf('>', open)
        if (openEnd < 0) return null
        val after = xml.getOrNull(open + 1 + tag.length)
        if (after == '>' || after == '/' || after?.isWhitespace() == true) return openEnd
        cursor = open + 1
    }
}

/**
 * 取 `<Tag …>…</Tag>` **之间**的内容；元素缺席（或只有自闭 `<Tag/>`）返回 null。
 *
 * ⛔ 不能简单 `indexOf("<$tag>")` —— 这一族里带属性的元素不止一个
 * （`<VideoParamAttribute Num="1">`、`<RegionList Num="2">`），写死收尾 `>` 会**永不命中**，
 * 而现象是「设备回了 OK 但没数据」，两侧都不报错。
 */
internal fun configBlockBody(xml: String, tag: String): String? {
    val openEnd = openTagEnd(xml, tag) ?: return null
    if (xml[openEnd - 1] == '/') return null
    val close = xml.indexOf("</$tag>", openEnd)
    if (close < 0) return null
    return xml.substring(openEnd + 1, close)
}

/** 元素**在场**但自闭（`<Tag/>`、`<Tag X="1"/>`、`<Tag />`）—— 与"元素根本不在"是两件事。 */
internal fun isSelfClosedBlock(xml: String, tag: String): Boolean {
    val openEnd = openTagEnd(xml, tag) ?: return false
    return xml[openEnd - 1] == '/'
}

/**
 * 块"拿不到内容"时的**统一返回值** —— 7 个类型的 `parse()` 第一行共用。
 *
 * ⛔ 把「平台没发这一块」与「块在场但自闭」合成一个 `null`（都当 Absent）是本族最隐蔽的
 * 一类静默吞掉：平台发了一个**结构上不成立**的报文（`<PictureMask/>` 缺 XSD 要求的
 * 必选子元素），设备侧一声不响 —— 现场看到的是"配了没生效"，而 SIP trace 早已过期。
 * 所以自闭形态走 [ConfigParse.Rejected]（记 warn、不动状态），只有**元素真的不在**才是
 * [ConfigParse.Absent]（正常路径、不打日志）。
 */
internal fun absentOrEmptyBlock(xml: String, tag: String): ConfigParse<Nothing> =
    if (isSelfClosedBlock(xml, tag)) {
        ConfigParse.Rejected("元素在场但无内容（<$tag/>）")
    } else {
        ConfigParse.Absent
    }

/** 取子元素的**整数**值；缺席或非整数返回 null（不补 0 —— 见各 `parse` 的注释）。 */
internal fun childInt(body: String, tag: String): Int? =
    ManscdpParser.tagValue(body, tag)?.trim()?.toIntOrNull()

/**
 * 一次配置块解析的结果 —— 这一族 **6 个类型共用**。
 *
 * ⛔ 三态必须分开，**不许合并成 `T?`**。合并之后调用方只能看到「没拿到值」，
 * 于是「平台没配这一项」（正常，不该打 warn）与「平台配了但内容非法」（要打 warn、
 * 要能定位是哪一条）会落进同一个分支 —— 现场就分不清是设备不支持、平台没发，
 * 还是平台发错了。本仓为"日志里分不清"这类问题反复付过代价。
 *
 * 三态对应的设备行为**完全一致**：**都不动状态**（回读保留原值 / 出厂默认）。
 * 区别只在日志与可观测性，所以调用方应当写成
 * `when (result) { is Accepted -> …, is Rejected -> log(warn), Absent -> Unit }`。
 */
sealed interface ConfigParse<out S> {
    /** 报文里**没有**这个块 —— 平台这一路没配。正常路径，不记日志。 */
    data object Absent : ConfigParse<Nothing>

    /** 块在，但内容非法（必选缺 / 取值越界 / 条数超上限）。**不动状态**，记 warn。 */
    data class Rejected(val reason: String) : ConfigParse<Nothing>

    /** 收下。 */
    data class Accepted<S>(val state: S) : ConfigParse<S>
}

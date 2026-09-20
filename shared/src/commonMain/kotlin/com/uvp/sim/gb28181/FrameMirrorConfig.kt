package com.uvp.sim.gb28181

import kotlinx.serialization.Serializable

/**
 * GB/T 28181-2022 **A.2.1.23 画面翻转配置类型**（`frameMirrorCfgType`）的当前取值。
 *
 * ⛔ 标准里它是 **`simpleType`（integer 0~3 的枚举）**，不是 complexType ——
 * 所以线上形态是**元素体直接就是那个整数**，没有任何子元素：
 * ```xml
 * <FrameMirror>1</FrameMirror>
 * ```
 * 别照其它类型的习惯去给它造 `<Mode>` / `<Value>` 之类的子元素（标准里没有，
 * 对端解不出来且不报错）。
 *
 * 值域（A.2.1.23 的 enumeration，逐个抄自原文）：
 *   - `0` 不启用镜像，基准画面
 *   - `1` 水平镜像（左右翻转）
 *   - `2` 上下镜像（上下翻转）
 *   - `3` 中心镜像（上下左右都翻转）
 *
 * ⛔ **值域以本标准原文为准，不要拿对端的下拉框当基准**：平台侧前端的 `MIRROR_OPTIONS`
 * 曾经按海康 ISP 口径把 **1/2 写反**（那是 ISP 的语义，不是国标的），2026-09 已对齐国标。
 * 排查这类"两侧都觉得自己对"的偏差时，唯一裁判是 A.2.1.23 的 enumeration 原文。
 * 渲染侧同源的那份换算是 `FrameMirrorTransform.of()`（Android GL 与 iOS CoreImage 两处
 * 共用；⛔ 别再加第三个消费端，尤其别加到本机 3D 画布上），改值域要同时看它。
 */
@Serializable
data class FrameMirrorState(
    /** wire 上的整数取值 0~3。默认 0 = 不启用镜像（设备出厂就是基准画面）。 */
    val value: Int = 0,
) {
    /**
     * 人读串，**只给 UI 与日志用，绝不进报文**（报文里必须是 [value] 这个整数）。
     * 同 `VideoParamAttribute` 的口径：码值 ↔ 人读串的换算只允许出现在展示层。
     */
    val displayLabel: String
        get() = when (value) {
            0 -> "不启用（基准画面）"
            1 -> "水平镜像（左右翻转）"
            2 -> "上下镜像（上下翻转）"
            3 -> "中心镜像（上下左右都翻转）"
            else -> "非法值 $value"
        }
}

/**
 * `FrameMirror` 的取值表、出厂默认与线格式。
 *
 * ⛔ **越界值一律拒绝落库**（值域是 XSD 里封闭的 `enumeration`，0~3 之外没有定义）。
 *
 * 为什么这里**不能像 `VideoParamAttribute` 那样"宽松收"**：那个类型的取值范围是开放的
 * （分辨率 / 帧率都是 string），设备收下什么就回什么，回读对账因此还能自洽；而这个类型
 * 一旦落库一个 7，回读就会一直吐 `<FrameMirror>7</FrameMirror>` —— 平台侧解析器判协议非法，
 * **这台设备从此永久只能回非法应答**，而现象指向"查询"而不是当初那条越界报文。
 * 本仓为同一形态踩过一次（看守位 `PresetIndex` 越界会把这台设备钉死，见
 * `PresetHandler.handleHomePosition` 的钳位注释）。
 *
 * 代价：平台若发越界值，回读会与下发不一致（平台判 mismatch）—— 那正是"设备没接受"的诚实答案。
 */
object FrameMirrorConfig {

    /** 值域上下限（A.2.1.23 的 enumeration）。 */
    const val MIN_VALUE = 0
    const val MAX_VALUE = 3

    fun isValid(value: Int): Boolean = value in MIN_VALUE..MAX_VALUE

    /**
     * 出厂默认 = 不启用镜像。设备不配置就是基准画面 —— 这是**物理事实**，
     * 不是"给个兜底默认值"，也不需要从 [com.uvp.sim.config.SimConfig] 派生。
     */
    val DEFAULT = FrameMirrorState(value = 0)

    /**
     * 解析下发报文。三种结果见 [ConfigParse]：非法一律 [ConfigParse.Rejected]（不动状态）。
     *
     * 注意 `<FrameMirror/>`（自闭、值为空）按 **Rejected** 处理，不是 Absent ——
     * 元素在场就说明平台确实点了这一项，只是没给值。
     */
    fun parse(xml: String): ConfigParse<FrameMirrorState> {
        val body = configBlockBody(xml, DeviceConfigBlock.FrameMirror.configType)
            ?: return absentOrEmptyBlock(xml, DeviceConfigBlock.FrameMirror.configType)
        val raw = body.trim()
        if (raw.isEmpty()) return ConfigParse.Rejected("元素为空（缺取值）")
        val value = raw.toIntOrNull() ?: return ConfigParse.Rejected("取值 `$raw` 不是整数")
        if (!isValid(value)) {
            return ConfigParse.Rejected("取值 $value 越界（合法 $MIN_VALUE~$MAX_VALUE）")
        }
        return ConfigParse.Accepted(FrameMirrorState(value))
    }

    /**
     * 回读应答块。
     *
     * ⛔ simpleType：元素体就是整数，**不产生任何子元素**，也不换行缩进
     * （与其余块一样只用一个结尾换行，由调用方统一处理 CRLF）。
     */
    fun render(state: FrameMirrorState): String = "<FrameMirror>${state.value}</FrameMirror>\n"
}

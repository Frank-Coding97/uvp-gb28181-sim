package com.uvp.sim.gb28181

/**
 * MANSCDP body 文本节点转义。
 *
 * 仅处理 5 个 XML 1.0 必须转义的字符,目标是防止 Catalog/Alarm/Notify 等
 * MANSCDP body 在节点名 / 字段值含 `<`、`&`、`</Item>` 等内容时
 * 产生畸形 XML 或被注入额外标签。
 *
 * 用法:仅对**文本节点内容**调用(`<Tag>${escapeXmlText(v)}</Tag>`)。
 * 属性值因 MANSCDP body 当前未拼接外部属性,这里只做文本节点这一档。
 */
internal fun escapeXmlText(value: String): String {
    if (value.isEmpty()) return value
    val sb = StringBuilder(value.length + 8)
    for (c in value) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

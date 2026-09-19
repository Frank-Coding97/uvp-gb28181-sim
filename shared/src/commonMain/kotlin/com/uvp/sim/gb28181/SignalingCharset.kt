package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion

/**
 * SIP 信令字符集(GB/T 28181 §6.10)。
 *
 * 原文口径(**两版都核过**):
 *   - 2016 §6.10「联网系统与设备的 SIP 信令字符集**宜**采用 GB 2312 编码格式。」
 *   - 2022 §6.10「联网系统与设备的 SIP 信令字符集**应**采用 GB 18030 编码格式。」
 *
 * 即 2022 把 2016 的推荐项(宜)升成强制项(应),编码也从 GB2312 升到 GB18030。所以
 * 「声明 GB2312 但实际发 UTF-8 字节」在 2022 下不是"宽松实现",而是**不合规**:
 * 平台侧 `manscdp.DecodeProfiledXML` 是**声明优先**的(GB2312 → GBK 解码器),
 * 拿 UTF-8 字节去按 GBK 解,中文必乱码;下行同理(平台发真 GB18030 字节,本端按 UTF-8 解)。
 */
enum class SignalingCharset(val xmlLabel: String) {
    /**
     * 2016 口径。实际编码用 **GBK** —— 平台侧 `encodingForLabel` 对 GB2312 也是取
     * `simplifiedchinese.GBK`(见其注释「GBK is the compatible superset used by deployed
     * devices」),两侧取同一条兼容超集,免得 GB2312 严格子集编不出生僻字。
     */
    GB2312("GB2312"),

    /** 2022 口径(§6.10 应)。GB18030 是 GBK 的超集,能覆盖全部 Unicode 码位。 */
    GB18030("GB18030"),

    /**
     * 非国标口径,只为兼容存量与自测保留。
     *
     * ⛔ 不要再用它当"和实际字节一致"的挡箭牌:那句注释写于 2016 口径下,
     * 2022 已把字符集定为**应 GB18030**,UTF-8 不再是可选项。
     */
    UTF8("UTF-8");

    companion object {
        /**
         * 版本 → 信令字符集。
         *
         * 入参请传**有效版本**(`min(本机声明, 平台声明)`,见 `GbVersionNegotiation`):
         * 对面是 2016 时按 GB18030 出站,2016 平台不一定认得。
         */
        fun of(version: GbVersion): SignalingCharset = when (version) {
            GbVersion.V2016 -> GB2312
            GbVersion.V2022 -> GB18030
        }

        /**
         * XML 声明里的编码名 → 字符集。取值表跟平台 `manscdp.canonicalCharset` 一一对应,
         * 两侧认同一批别名(否则会出现"本端声明 GBK、平台不认"的哑火)。
         */
        fun parse(label: String?): SignalingCharset? = when (label?.trim()?.uppercase()) {
            null, "" -> null
            "GB2312", "GB_2312-80", "GB_2312-1980", "GBK", "CP936", "MS936", "WINDOWS-936" -> GB2312
            "GB18030", "GB-18030" -> GB18030
            "UTF-8", "UTF8", "UNICODE-1-1-UTF-8" -> UTF8
            else -> null
        }
    }
}

/** 平台/设备都用这个编码把 String 变成 wire 上的字节。 */
expect fun encodeSignalingText(text: String, charset: SignalingCharset): ByteArray

/** 把 wire 上的字节按指定字符集解回 String。 */
expect fun decodeSignalingText(bytes: ByteArray, charset: SignalingCharset): String

// XmlEncodingLabelRegex 只扫「XML 声明」这个 prolog,不在正文里瞎找 ——
// 正文里出现 `encoding="..."` 的概率不为零(比如带 CDATA 的描述字段)。
private val XML_PROLOG_REGEX = Regex("""(?is)<\?xml\b[^>]*\?>""")
private val XML_DECLARATION_LABEL_REGEX =
    Regex("""encoding\s*=\s*["']([A-Za-z0-9_\-]+)["']""", RegexOption.IGNORE_CASE)

private const val CHARSET_SCAN_WINDOW = 1024

/**
 * 从报文**字节**里读 XML 声明中的编码名。
 *
 * 只看前 1KB,且按 ASCII 区间逐字节筛查:三种候选编码(GB2312/GB18030/UTF-8)的
 * 声明部分都是纯 ASCII,所以这段不需要先知道编码就能读出来 —— 这正是"按声明解码"
 * 能做到自举的原因,也对应平台 `xmlEncodingLabel` 的做法。
 */
fun declaredSignalingCharset(body: ByteArray): SignalingCharset? {
    val prolog = XML_PROLOG_REGEX.find(asciiHead(body))?.value ?: return null
    return SignalingCharset.parse(XML_DECLARATION_LABEL_REGEX.find(prolog)?.groupValues?.get(1))
}

/** 非 ASCII 字节一律换成空格,保证正则只可能命中真声明。 */
private fun asciiHead(body: ByteArray): String {
    val n = minOf(body.size, CHARSET_SCAN_WINDOW)
    val sb = StringBuilder(n)
    for (i in 0 until n) {
        val v = body[i].toInt() and 0xFF
        sb.append(if (v in 0x20..0x7E) v.toChar() else ' ')
    }
    return sb.toString()
}

/**
 * 把 XML 声明改写成 [charset]。
 *
 * 所有报文体都走这里,是为了让**声明与字节永远同源** —— 本仓原来的病灶就是
 * 23 处 builder 各自内联一个声明字面量、而字节出口统一 UTF-8,两边可以各自漂移。
 * ⛔ 别在 builder 里再写死 `encoding="..."`。
 */
fun rewriteXmlDeclaration(xml: String, charset: SignalingCharset): String {
    val declaration = """<?xml version="1.0" encoding="${charset.xmlLabel}"?>"""
    val prolog = XML_PROLOG_REGEX.find(xml) ?: return "$declaration\r\n$xml"
    return xml.replaceRange(prolog.range, declaration)
}

/**
 * 拆掉 XML 声明,只留正文(保留原行尾)。
 *
 * 供给"拿自己 build 出来的报文体再 parse 回去"的场景用 —— 这类地方以前写的是
 * `removePrefix("<?xml version=\"1.0\" encoding=\"GB2312\"?>")`,一旦出站字符集按版本
 * 变成 GB18030,前缀就对不上、**剥离静默失败**(不抛异常,后续 startsWith("<Response>") 才返 null)。
 * 用正则认整个声明,声明写什么编码都不影响。
 */
fun stripXmlDeclaration(xml: String): String =
    XML_PROLOG_REGEX.find(xml)?.let { xml.removeRange(it.range) } ?: xml

/** 出站:改写声明 + 真转码。**返回的字节与报文里声明的编码必定一致**(见下方兜底)。
 *
 * 兜底的意义:某个平台的编码器不支持目标字符集时(编码器返回了别的字节),直接发出去
 * 就又回到"声明 GB18030、字节 UTF-8"的老病灶。这里改成**降级但自洽** —— 连声明一起退回 UTF-8。
 * 代价是不合规,但至少对面不会整段乱码,且从声明就能一眼看出降级发生了。
 */
fun encodeSignalingBody(xml: String, charset: SignalingCharset): ByteArray {
    val declared = rewriteXmlDeclaration(xml, charset)
    val bytes = encodeSignalingText(declared, charset)
    if (declaredSignalingCharset(bytes) == charset) return bytes
    return encodeSignalingText(
        rewriteXmlDeclaration(xml, SignalingCharset.UTF8),
        SignalingCharset.UTF8,
    )
}

/**
 * 入站:按报文**自己声明的**编码解码;没有声明(或声明不认识)才回退到 [fallback]。
 *
 * 回退值传「当前有效版本对应的字符集」,而不是固定 UTF-8 —— 对面没声明时按协商结果猜,
 * 比按本端习惯猜更接近对端意图。
 */
fun decodeSignalingBody(body: ByteArray, fallback: SignalingCharset): String {
    val declared = declaredSignalingCharset(body) ?: return decodeSignalingText(body, fallback)
    return decodeSignalingText(body, declared)
}

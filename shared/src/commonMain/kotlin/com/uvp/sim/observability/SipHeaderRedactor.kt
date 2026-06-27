package com.uvp.sim.observability

/**
 * P2-7 (audit §3) — SIP 协议头脱敏共享 helper。
 *
 * 单一真相来源:SystemLogger 内部 sanitize + UI 复制路径(SipMapper / SipLogRow /
 * SipFlowHelpers / LogExport)都调这个 helper,避免各处重复正则/硬编码脱敏逻辑。
 *
 * 设计原则:
 * - Authorization / Proxy-Authorization / WWW-Authenticate / Proxy-Authenticate
 *   这些 HTTP/SIP 风格多 token 头(如 `Digest username="x", nonce="y", response="z"`)
 *   整行包含敏感字段,统一收敛成 `<header>: <redacted>` 输出
 * - 单个 header name+value 检查:提供 [redactHeader] 给 mapper 逐头过滤用
 * - 全文本流脱敏:[redact] 处理完整 SIP 报文字符串(多行 headers)
 * - 大小写不敏感:SIP 头名规范不区分大小写
 */
object SipHeaderRedactor {

    /**
     * 敏感 SIP 头清单 — HTTP Digest Auth 相关。
     * WWW-Authenticate / Proxy-Authenticate 是服务端挑战(含 nonce / realm),
     * Authorization / Proxy-Authorization 是客户端响应(含 username / response / cnonce)。
     * 全部不应出现在日志/剪贴板里,否则可能被字典攻击或重放。
     */
    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "www-authenticate",
        "proxy-authenticate"
    )

    /**
     * SIP 协议头整行脱敏正则 — 匹配到行尾(`\n` 或 `\r\n` 或字符串末尾)。
     *
     * 用于 [redact] 对完整 SIP 报文字符串做全文扫描替换。
     */
    private val sipAuthHeaderRegex = Regex(
        "(authorization|proxy-authorization|www-authenticate|proxy-authenticate)" +
            "\\s*:\\s*[^\\r\\n]*",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 通用 key=value 风格密码 / 令牌脱敏 — 覆盖 `password=xxx` / `token: xxx` /
     * `secret = xxx` 等场景。一次只吞到第一个空白前,不咬下整行。
     *
     * 注意:SIP `Authorization:` 头不用这个(会漏 nonce / response),而是走
     * [sipAuthHeaderRegex] 整行脱敏。
     */
    private val kvRedactRegex = Regex(
        "(password|secret|token)\\s*[=:]\\s*\\S+",
        RegexOption.IGNORE_CASE
    )

    /**
     * 全文本流脱敏 — 处理完整 SIP 报文字符串(多行 headers + body)。
     *
     * 先把 SIP 风格的整行 Authorization 头脱掉,再过 kv 风格 fallback。
     * SystemLogger.sanitize / UI 复制路径统一调这个。
     *
     * @param rawText 原始日志/报文文本(可能包含多行 SIP headers)
     * @return 脱敏后文本 — 敏感头整行替换为 `<header>: <redacted>`,kv 风格替换为 `<key>=****`
     */
    fun redact(rawText: String): String {
        // 先把 SIP 风格的整行 Authorization 头脱掉
        val afterAuthHeader = rawText.replace(sipAuthHeaderRegex) { match ->
            val headerName = match.groupValues[1]
            "$headerName: <redacted>"
        }
        // 再过 kv 风格 fallback
        return afterAuthHeader.replace(kvRedactRegex) { match ->
            "${match.groupValues[1].lowercase()}=****"
        }
    }

    /**
     * 单个 header name+value 检查 — 如果是敏感头,返回 `<redacted>`,否则原样返回。
     *
     * 用于 SipMapper 逐头过滤:遍历 headers.map { redactHeader(it.name, it.value) }。
     *
     * @param name SIP 头名(如 "Authorization" / "From" / "CSeq")
     * @param value SIP 头值(如 "Digest username=..." / "<sip:xxx>" / "102 INVITE")
     * @return 如果是敏感头,返回 `<redacted>`;否则原样返回 value
     */
    fun redactHeader(name: String, value: String): String {
        return if (name.lowercase() in SENSITIVE_HEADERS) {
            "<redacted>"
        } else {
            value
        }
    }
}

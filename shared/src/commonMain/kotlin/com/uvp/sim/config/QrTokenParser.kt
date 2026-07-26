package com.uvp.sim.config

/**
 * 扫码解析结果:平台 baseUrl(scheme + authority)+ 一次性 token。
 */
data class QrScanTarget(val baseUrl: String, val token: String)

/**
 * 扫码字符串 → [QrScanTarget](plan §2.2 / §5.3)。
 *
 * 只认 fragment 形式:`http(s)://<host>[:port]/gb28181/qr#t=<22 字符 token>`。
 *
 * **query 形式(`?t=`)必须拒绝**:token 若进 query,`gin.Logger()` 的访问日志会把它写进
 * 落盘日志,而通用扫码 App 一扫就 GET 消费掉一次性 token。平台端已改成 fragment + POST,
 * sim 侧同步只认 fragment,顺带成为 D6 的回归防线。
 */
object QrTokenParser {

    private const val QR_PATH = "/gb28181/qr"
    private const val TOKEN_PARAM = "t="
    private const val TOKEN_LENGTH = 22

    fun parseQrScan(raw: String): QrScanTarget? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null

        val hashIndex = text.indexOf('#')
        if (hashIndex < 0) return null

        val beforeHash = text.substring(0, hashIndex)
        // token 只能在 fragment 里;出现 query 串一律拒绝(既防 `?t=` 也防混合形式)
        if (beforeHash.contains('?')) return null

        val afterScheme = beforeHash.substring(schemeEnd + 3)
        val slashIndex = afterScheme.indexOf('/')
        if (slashIndex <= 0) return null
        val authority = afterScheme.substring(0, slashIndex)
        val path = afterScheme.substring(slashIndex).trimEnd('/')
        if (path != QR_PATH) return null

        val fragment = text.substring(hashIndex + 1)
        if (!fragment.startsWith(TOKEN_PARAM)) return null
        val token = fragment.substring(TOKEN_PARAM.length)
        if (!isValidToken(token)) return null

        return QrScanTarget(baseUrl = "$scheme://$authority", token = token)
    }

    /** 平台 token = `crypto/rand` 16 byte → base64url 无填充,恒 22 字符。 */
    private fun isValidToken(token: String): Boolean =
        token.length == TOKEN_LENGTH && token.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        }
}

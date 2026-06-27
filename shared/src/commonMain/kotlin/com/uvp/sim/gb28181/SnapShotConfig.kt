package com.uvp.sim.gb28181

/**
 * GB/T 28181-2022 §9.5 图像抓拍 — 平台下发的 SnapShotConfig 命令载荷。
 *
 * - [sessionId] 平台用来追踪本次抓拍会话的 ID,设备在每张图上传完成后回 NOTIFY 时回填
 * - [uploadUrl] 平台指定的 HTTP 上传目标。scheme=http/https,host 非空,
 *   末尾 `/` 会被设备自动追加文件名(交给上传器处理)
 * - [snapNum]   抓拍张数,clamp 到 1..10
 * - [intervalMs] 张间间隔(毫秒)。GB 标准里以秒下发,parser 转 ms,clamp 到 0..60000
 */
data class SnapShotConfig(
    val sessionId: String,
    val uploadUrl: String,
    val snapNum: Int,
    val intervalMs: Long
)

/**
 * 解析平台下发的 `<SnapShotConfig>` MANSCDP 子节点。
 *
 * 解析失败(必填缺 / URL 非法)返回 null,调用方应回 SIP 200 OK 但忽略本次配置,
 * 避免平台无限重试。
 */
object SnapShotConfigParser {

    private const val SNAP_NUM_MIN = 1
    private const val SNAP_NUM_MAX = 10
    private const val INTERVAL_MS_MIN = 0L
    private const val INTERVAL_MS_MAX = 60_000L

    fun parse(xml: String): SnapShotConfig? {
        if (!xml.contains("<SnapShotConfig>")) return null

        val sessionId = ManscdpParser.tagValue(xml, "SessionID")?.takeIf { it.isNotBlank() }
            ?: return null
        val uploadUrl = ManscdpParser.tagValue(xml, "UploadURL")?.takeIf { it.isNotBlank() }
            ?: return null
        if (!isValidUploadUrl(uploadUrl)) return null

        val snapNum = ManscdpParser.tagValue(xml, "SnapNum")?.toIntOrNull()
            ?.coerceIn(SNAP_NUM_MIN, SNAP_NUM_MAX)
            ?: 1

        val intervalSec = ManscdpParser.tagValue(xml, "Interval")?.toLongOrNull() ?: 0L
        val intervalMs = (intervalSec * 1000L).coerceIn(INTERVAL_MS_MIN, INTERVAL_MS_MAX)

        return SnapShotConfig(
            sessionId = sessionId,
            uploadUrl = uploadUrl,
            snapNum = snapNum,
            intervalMs = intervalMs
        )
    }

    /**
     * 弱校验:scheme=http/https + host 非空。不预探活(WVP 不下发,联调多用本地 IP,HEAD 反误判)。
     */
    private fun isValidUploadUrl(url: String): Boolean {
        val scheme = when {
            url.startsWith("http://", ignoreCase = true) -> "http://"
            url.startsWith("https://", ignoreCase = true) -> "https://"
            else -> return false
        }
        val rest = url.substring(scheme.length)
        // host 截到 `/` 或 `?` 或 `:`;允许 `host:port`、`host/path`、`host`
        val hostEnd = rest.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) rest.length else it }
        val hostPart = rest.substring(0, hostEnd)
        // host 部分允许带 :port,host 自身需要非空
        val host = hostPart.substringBefore(':')
        return host.isNotBlank()
    }
}

/**
 * P2-6 (audit §3) — UploadURL 严格校验器(SSRF / 数据外传防御)。
 *
 * 在平台下发 SnapShotConfig.uploadUrl 前或 SnapshotUploadEngine 上传前调用,拒绝:
 *   - scheme 非 http/https
 *   - host 不在 [allowList] 中(精确字面量匹配,v1 暂不支持 CIDR)
 *   - host 是 loopback (127.0.0.0/8, ::1)
 *   - host 是 link-local (169.254.0.0/16, fe80::/10)
 *   - host 是 multicast (224.0.0.0/4, ff00::/8)
 *   - host 是元数据地址字面量 (localhost, 0.0.0.0, metadata.google.internal 等)
 *
 * **零信任默认**: [allowList] 为空时拒绝任意 URL,避免真实抓拍接通后立刻 SSRF。
 * 用户必须在配置页手动加上传白名单后才允许上传。
 *
 * @param url 平台下发的 UploadURL
 * @param allowList 允许的 host 字面量列表,空 list = 拒绝任意
 * @return true = 通过;false = 拒绝
 */
object SnapShotUploadUrlValidator {

    fun isValidUploadUrlStrict(url: String, allowList: List<String>): Boolean {
        // 1. scheme http/https
        val scheme = when {
            url.startsWith("http://", ignoreCase = true) -> "http://"
            url.startsWith("https://", ignoreCase = true) -> "https://"
            else -> return false
        }
        // 2. extract host
        val host = extractHost(url, scheme) ?: return false
        if (host.isBlank()) return false
        // 3. reject dangerous literals first (even if in allowList — defense in depth)
        if (isDangerousLiteralHost(host)) return false
        // 4. reject ipv4 in dangerous CIDR (loopback / link-local / multicast)
        if (isDangerousIpv4(host)) return false
        // 5. reject ipv6 (loopback, link-local, multicast)
        if (isDangerousIpv6(host)) return false
        // 6. allowList check — host must match a list entry literally
        if (allowList.isEmpty()) return false // zero-trust: empty allowList = deny all
        return host in allowList
    }

    /**
     * 从 URL 提取 host(不含 port,不含 scheme)。
     *
     * IPv6 格式 `http://[::1]:8080/` → host = `::1`(去括号)。
     * IPv4/域名 `http://host:port/` → host = `host`(去 port)。
     */
    private fun extractHost(url: String, scheme: String): String? {
        val rest = url.substring(scheme.length)
        // IPv6 in brackets: [::1]:port → host = ::1 (no brackets)
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close <= 1) return null
            return rest.substring(1, close)
        }
        val hostEnd = rest.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) rest.length else it }
        val hostPart = rest.substring(0, hostEnd)
        // strip :port (but only first colon — IPv6 already excluded by bracket case)
        return hostPart.substringBefore(':')
    }

    /**
     * 危险字面量 host — 元数据 / 特殊地址。
     *
     * 包括云厂商元数据地址(AWS/GCP/Azure),loopback 文字表示,全 0 地址。
     */
    private val dangerousLiterals = setOf(
        "localhost",
        "0.0.0.0",
        "::",
        "::1",
        "metadata.google.internal",
        "metadata",
        "instance-data",
        "metadata.azure.com",
        "169.254.169.254", // AWS metadata IP (also caught by link-local CIDR check)
    )

    private fun isDangerousLiteralHost(host: String): Boolean =
        host.lowercase() in dangerousLiterals

    /**
     * IPv4 危险 CIDR 检测:loopback / link-local / multicast。
     *
     * - 127.0.0.0/8  → loopback
     * - 169.254.0.0/16 → link-local (AWS metadata 也在此)
     * - 224.0.0.0/4  → multicast
     * - 0.0.0.0      → wildcard/unspecified
     */
    private fun isDangerousIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val o = parts.map { it.toIntOrNull() ?: return false }
        if (o.any { it !in 0..255 }) return false
        // 127.0.0.0/8 loopback
        if (o[0] == 127) return true
        // 169.254.0.0/16 link-local
        if (o[0] == 169 && o[1] == 254) return true
        // 224.0.0.0/4 multicast (first octet 224-239)
        if (o[0] in 224..239) return true
        // 0.0.0.0 wildcard
        if (o[0] == 0 && o[1] == 0 && o[2] == 0 && o[3] == 0) return true
        return false
    }

    /**
     * IPv6 危险地址检测:loopback / link-local / multicast。
     *
     * - ::1         → loopback
     * - ::          → unspecified
     * - fe80::/10   → link-local
     * - ff00::/8    → multicast
     *
     * v1 暂用字符串前缀匹配,不引入完整 IPv6 解析库。
     */
    private fun isDangerousIpv6(host: String): Boolean {
        val h = host.lowercase()
        // exact ::1 / ::
        if (h == "::1" || h == "::") return true
        // fe80::/10 (link-local) — fe80:: ... febf::
        // simplified: match fe80-febf prefix
        if (h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")) {
            return true
        }
        // ff00::/8 ipv6 multicast
        if (h.startsWith("ff")) return true
        return false
    }
}

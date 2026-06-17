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

package com.uvp.sim.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 在线升级清单 —— 官网 `upgrades/android/stable/latest.json` 的内容。
 *
 * 字段口径与桌面端 Tauri 清单保持同一套思路（版本 + 说明 + 直链 + 校验），
 * 但安卓这边**不用签名文件，改用 APK 自身的 sha256 + 安装包证书比对**：
 *
 * - Android 侧安装器必须在系统层确认「同一签名才能覆盖安装」，所以拿包内签名
 *   和已安装包的签名做比对，比另造一套 minisign 体系更贴合平台；
 * - sha256 负责拦住下载损坏 / CDN 回源错包，两者互补，缺一不可。
 *
 * 所有字段都给默认值 + `ignoreUnknownKeys`，这样服务端加字段不会把老客户端
 * 卡死在「解析失败」上（宁可提示更新不可用，也不能整个入口崩掉）。
 */
@Serializable
data class UpdateManifest(
    /** 展示用版本号，形如 `1.0.12`；允许带 `v` 前缀，解析时统一去掉。 */
    val version: String = "",
    /**
     * 比较用版本码。安卓的 versionCode 是单调递增整数，比版本号字符串可靠
     * （字符串比较会把 1.0.9 判成比 1.0.11 大）。
     */
    val versionCode: Long = 0,
    /** 更新说明原文，通常是一段 `- xxx` 列表。 */
    val notes: String = "",
    /** 发布时间，ISO-8601 字符串，仅用于展示。 */
    @SerialName("pub_date") val publishedAt: String = "",
    /** 新版本要求的最低系统 API；0 表示清单没写，不做限制。 */
    val minSdk: Int = 0,
    /** 安装包信息。 */
    val apk: ApkPackage = ApkPackage(),
) {
    @Serializable
    data class ApkPackage(
        /** 安装包下载直链，必须是 https。 */
        val url: String = "",
        /** 安装包 sha256（64 位小写十六进制）。 */
        val sha256: String = "",
        /** 安装包字节数；0 表示未知，下载进度会退化成不确定态。 */
        val bytes: Long = 0,
    )

    /**
     * 这份清单能不能拿去下载安装。
     *
     * 判据只卡**会导致安装失败或安全风险**的项，不做「格式洁癖」：
     * 强制 https 是因为 APK 是最终执行体，不能被中间人换包；sha256 是唯一
     * 的完整性凭据，缺了就没法验证。版本号缺失则连「有没有新版」都判断不了。
     *
     * @return null 表示可用，否则是给用户看的失败原因。
     */
    fun validationError(): String? {
        if (trimmedVersion().isEmpty()) return "更新清单缺少版本号。"
        if (!apk.url.startsWith("https://")) return "更新清单里的安装包地址无效。"
        if (!UPDATE_SHA256_REGEX.matches(apk.sha256)) return "更新清单缺少有效的安装包校验值。"
        if (apk.bytes < 0) return "更新清单里的安装包大小无效。"
        return null
    }

    fun trimmedVersion(): String = normalizeVersion(version)
}

// 放在文件级而不是 companion：@Serializable 会给 UpdateManifest 生成
// Companion.serializer()，companion 一旦声明成 private，解析器就调不到它。
private val UPDATE_SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

/** 去掉用户/清单可能带上的 `v` 前缀，统一成 `1.2.3` 形态再进 UI。 */
fun normalizeVersion(version: String): String =
    version.trim().removePrefix("v").removePrefix("V")

/**
 * 版本判定。
 *
 * 优先用 versionCode（安卓原生口径，单调递增）；只有清单没给 code 时才退回
 * 语义化版本号逐段比较 —— 老版本清单/手写清单可能只有 version 字段，
 * 这时宁可比字符串，也不要直接判定「没有更新」把用户锁死。
 */
object UpdateVersion {

    /** 远端是否比当前安装的版本新。两边都无效时一律返回 false（不打扰用户）。 */
    fun isNewer(remote: UpdateManifest, currentVersionCode: Long, currentVersionName: String): Boolean {
        val remoteCode = remote.versionCode
        if (remoteCode > 0 && currentVersionCode > 0) {
            return remoteCode > currentVersionCode
        }
        val remoteName = remote.trimmedVersion()
        val currentName = normalizeVersion(currentVersionName)
        if (remoteName.isEmpty() || currentName.isEmpty()) return false
        return compare(remoteName, currentName) > 0
    }

    /**
     * 逐段数值比较，缺位补 0：`1.0.12` > `1.0.9`，`1.0` == `1.0.0`。
     *
     * 预发布后缀（`1.0.0-beta`）排在对应正式版**之前**：段数更多的一侧如果是
     * 纯数字（`1.0.0` 对 `1.0`）按补 0 处理，带非数字则视为预发布。
     * 任何格式都不会抛异常 —— 版本号写歪不该让更新入口崩。
     *
     * @return 正数表示 [a] 更新，负数表示 [b] 更新，0 表示相等。
     */
    fun compare(a: String, b: String): Int {
        val left = splitSegments(a)
        val right = splitSegments(b)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val l = left.getOrNull(index)
            val r = right.getOrNull(index)
            val byNumber = (l?.numeric ?: 0L).compareTo(r?.numeric ?: 0L)
            if (byNumber != 0) return byNumber
            if (l != null && r != null) {
                if (l.raw != r.raw) return l.raw.compareTo(r.raw)
                continue
            }
            // 只有一侧还有段：带非数字后缀的是预发布，正式版更大。
            if (l == null && r != null && r.isPrerelease) return 1
            if (r == null && l != null && l.isPrerelease) return -1
        }
        return 0
    }

    private data class Segment(val numeric: Long, val raw: String) {
        /** 段里出现非数字字符（`beta`、`rc1`）即视为预发布标记。 */
        val isPrerelease: Boolean get() = raw.any { !it.isDigit() }
    }

    private fun splitSegments(version: String): List<Segment> =
        normalizeVersion(version)
            .split('.', '-', '+')
            .filter { it.isNotEmpty() }
            .map { raw ->
                val digits = raw.takeWhile { it.isDigit() }
                Segment(numeric = digits.toLongOrNull() ?: 0L, raw = raw)
            }
}

/**
 * 清单解析。单独抽出来是为了能在 commonTest 里跑：
 * 解析规则（缺字段 / 多字段 / 脏数据）是线上最容易出事的一段，必须可测。
 */
object UpdateManifestParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** @return 解析成功返回清单，格式错误 / 空内容返回 null。 */
    fun parse(text: String): UpdateManifest? {
        if (text.isBlank()) return null
        return runCatching { json.decodeFromString(UpdateManifest.serializer(), text) }.getOrNull()
    }
}

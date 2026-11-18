package com.uvp.sim.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 在线升级清单解析 + 版本判定的回归防线。
 *
 * 这些用例对应线上最容易出事的三类问题：清单字段被服务端改坏、版本号比较
 * 用字符串导致「1.0.9 > 1.0.11」、清单指向非 https 地址或没带校验值。
 */
class UpdateManifestTest {

    private val validJson = """
        {
          "version": "1.0.12",
          "versionCode": 10012,
          "notes": "- 修复 A\n- 新增 B",
          "pub_date": "2026-11-18T02:00:00Z",
          "minSdk": 26,
          "apk": {
            "url": "https://gitee.com/Frank-Coding/uvp-gb28181-sim/releases/download/v1.0.12/uvp-gb28181-sim-1.0.12.apk",
            "sha256": "${"a".repeat(64)}",
            "bytes": 36212267
          }
        }
    """.trimIndent()

    // ---------- 解析 ----------

    @Test
    fun `parses complete manifest`() {
        val manifest = assertNotNull(UpdateManifestParser.parse(validJson))
        assertEquals("1.0.12", manifest.version)
        assertEquals(10012L, manifest.versionCode)
        assertEquals(26, manifest.minSdk)
        assertEquals(36212267L, manifest.apk.bytes)
        assertTrue(manifest.notes.contains("修复 A"))
        assertNull(manifest.validationError())
    }

    @Test
    fun `ignores unknown fields so server can add columns`() {
        val text = validJson.replaceFirst("{", """{ "channel": "stable", "rollout": 30,""")
        val manifest = assertNotNull(UpdateManifestParser.parse(text))
        assertEquals("1.0.12", manifest.version)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val text = """{"version":"1.0.12","apk":{"url":"https://x/y.apk","sha256":"${"b".repeat(64)}"}}"""
        val manifest = assertNotNull(UpdateManifestParser.parse(text))
        assertEquals(0L, manifest.versionCode, "没给 versionCode 应为 0,由比较逻辑退回版本号")
        assertEquals(0L, manifest.apk.bytes)
    }

    @Test
    fun `broken json returns null instead of throwing`() {
        assertNull(UpdateManifestParser.parse(""))
        assertNull(UpdateManifestParser.parse("   "))
        assertNull(UpdateManifestParser.parse("not json at all"))
        assertNull(UpdateManifestParser.parse("""{"version": }"""))
    }

    @Test
    fun `numeric version in json does not crash the parser`() {
        // lenient 模式会把裸数字读成字符串。这类脏数据不该让解析抛异常，
        // 且必然倒在 validationError（缺 url / sha256）而不是被当成可用清单。
        val manifest = assertNotNull(UpdateManifestParser.parse("""{"version": 123}"""))
        assertNotNull(manifest.validationError())
    }

    // ---------- 清单可用性 ----------

    @Test
    fun `rejects plain http package url`() {
        val manifest = UpdateManifest(
            version = "1.0.12",
            apk = UpdateManifest.ApkPackage(
                url = "http://example.com/a.apk",
                sha256 = "c".repeat(64),
                bytes = 1,
            ),
        )
        assertNotNull(manifest.validationError(), "APK 是最终执行体,不能被明文 http 换包")
    }

    @Test
    fun `rejects missing or malformed sha256`() {
        val noHash = UpdateManifest(
            version = "1.0.12",
            apk = UpdateManifest.ApkPackage(url = "https://x/y.apk", sha256 = ""),
        )
        assertNotNull(noHash.validationError())

        val shortHash = noHash.copy(
            apk = noHash.apk.copy(sha256 = "abc123"),
        )
        assertNotNull(shortHash.validationError())
    }

    @Test
    fun `rejects manifest without version`() {
        val manifest = UpdateManifest(
            apk = UpdateManifest.ApkPackage(url = "https://x/y.apk", sha256 = "d".repeat(64)),
        )
        assertNotNull(manifest.validationError())
    }

    @Test
    fun `accepts uppercase sha256`() {
        val manifest = UpdateManifest(
            version = "1.0.12",
            apk = UpdateManifest.ApkPackage(url = "https://x/y.apk", sha256 = "A".repeat(64)),
        )
        assertNull(manifest.validationError())
    }

    // ---------- 版本判定 ----------

    @Test
    fun `versionCode decides when both sides provide it`() {
        val remote = UpdateManifest(version = "1.0.9", versionCode = 10009)
        assertFalse(
            UpdateVersion.isNewer(remote, currentVersionCode = 10011, currentVersionName = "1.0.11"),
            "远端 code 更小,即便版本号字符串看起来一样长也不能提示更新",
        )
    }

    @Test
    fun `newer versionCode wins`() {
        val remote = UpdateManifest(version = "1.0.12", versionCode = 10012)
        assertTrue(UpdateVersion.isNewer(remote, currentVersionCode = 10011, currentVersionName = "1.0.11"))
    }

    @Test
    fun `same versionCode is not an update`() {
        val remote = UpdateManifest(version = "1.0.12", versionCode = 10012)
        assertFalse(UpdateVersion.isNewer(remote, currentVersionCode = 10012, currentVersionName = "1.0.12"))
    }

    @Test
    fun `falls back to version string when manifest has no versionCode`() {
        val remote = UpdateManifest(version = "1.0.12", versionCode = 0)
        assertTrue(
            UpdateVersion.isNewer(remote, currentVersionCode = 10011, currentVersionName = "1.0.11"),
            "清单只有 version 时要按版本号比,否则老客户端永远收不到更新",
        )
        val older = UpdateManifest(version = "1.0.9", versionCode = 0)
        assertFalse(UpdateVersion.isNewer(older, currentVersionCode = 10011, currentVersionName = "1.0.11"))
    }

    @Test
    fun `compares numeric segments not raw strings`() {
        assertTrue(UpdateVersion.compare("1.0.12", "1.0.9") > 0)
        assertTrue(UpdateVersion.compare("1.2.0", "1.10.0") < 0)
        assertEquals(0, UpdateVersion.compare("1.0", "1.0.0"))
        assertEquals(0, UpdateVersion.compare("v1.0.0", "1.0.0"))
    }

    @Test
    fun `prerelease sorts before its release`() {
        assertTrue(UpdateVersion.compare("1.0.0", "1.0.0-beta") > 0)
        assertTrue(UpdateVersion.compare("1.0.0-beta", "1.0.0-alpha") > 0)
        assertTrue(UpdateVersion.compare("1.0.0-rc1", "1.0.0-beta") > 0)
    }

    @Test
    fun `normalizes v prefix for display`() {
        assertEquals("1.0.12", normalizeVersion(" v1.0.12 "))
        assertEquals("1.0.12", UpdateManifest(version = "V1.0.12").trimmedVersion())
    }
}

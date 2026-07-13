package com.uvp.sim.recording

import kotlinx.datetime.TimeZone
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringByAppendingPathComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 覆盖 [RecordingFileStore] 的 filePath 绝对↔相对转换,专门守护:
 *   - 相对路径 loadIndex 时能拼回当前 baseDir 的绝对路径
 *   - 绝对路径 persistIndex 时被剥成相对
 *   - **老 index.json 的绝对路径**(来自沙盒 UUID 已变的旧版包)通过 "/recordings/" 段
 *     兜底截取,实现跨沙盒的路径迁移(否则用户升级 App 后录像列表全变死链)
 *   - 幂等:相对路径进 toRelative / 绝对路径进 toAbsolute 都不变形
 */
class RecordingFileStorePathTest {

    private fun newStore() = RecordingFileStore(
        deviceIdSupplier = { "34020000001320000001" },
        timeZone = TimeZone.UTC,
    )

    private fun currentBaseDir(): String {
        val docs = (NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        ).firstOrNull() as? String) ?: "/tmp"
        return (docs as NSString).stringByAppendingPathComponent("recordings")
    }

    @Test
    fun toRelative_stripsCurrentBaseDir() {
        val store = newStore()
        val abs = (currentBaseDir() as NSString)
            .stringByAppendingPathComponent("34020000001320000001/20260713/103045.mp4")
        val rel = store.toRelative(abs)
        assertEquals("34020000001320000001/20260713/103045.mp4", rel)
    }

    @Test
    fun toRelative_preservesAlreadyRelativePath() {
        val store = newStore()
        val input = "34020000001320000001/20260713/103045.mp4"
        assertEquals(input, store.toRelative(input))
    }

    @Test
    fun toRelative_fallsBackForOldSandboxAbsolute() {
        // 模拟老包沙盒 UUID 已废弃的绝对路径 — 关键场景:App 升级后 baseDir 前缀变了,
        // 但结构里 /recordings/ 段是稳定的,可以救出相对部分,索引不至于全失效。
        //
        // 相对格式必须跟主分支一致(不含 "recordings/" 前缀),否则 toAbsolute 拼回时
        // 会出现 "<baseDir>/recordings/xxx" = "<Documents>/recordings/recordings/xxx" 双前缀。
        val store = newStore()
        val staleAbs =
            "/var/mobile/Containers/Data/Application/AAAA-BBBB-CCCC-DDDD/Documents/recordings/34020000001320000001/20260713/103045.mp4"
        val rel = store.toRelative(staleAbs)
        assertEquals("34020000001320000001/20260713/103045.mp4", rel)
    }

    @Test
    fun toRelative_preservesUnknownAbsolutePathAsIs() {
        // 完全不认识的绝对路径:既不在当前 baseDir 也不含 /recordings/ 段。
        // 保持原样,不做假迁移 —— 数据本就失效,让 UI 显示"文件不存在"是诚实行为。
        val store = newStore()
        val alien = "/tmp/randomStuff/foo.mp4"
        assertEquals(alien, store.toRelative(alien))
    }

    @Test
    fun toAbsolute_prependsBaseDirToRelative() {
        val store = newStore()
        val rel = "34020000001320000001/20260713/103045.mp4"
        val abs = store.toAbsolute(rel)
        // 结果必是绝对路径 + 以 baseDir 开头
        assertTrue(abs.startsWith("/"))
        assertTrue(abs.startsWith(currentBaseDir()))
        assertTrue(abs.endsWith(rel))
    }

    @Test
    fun toAbsolute_preservesAlreadyAbsolutePath() {
        val store = newStore()
        val abs = "/var/mobile/Containers/Data/Application/X/Documents/recordings/x.mp4"
        assertEquals(abs, store.toAbsolute(abs))
    }

    @Test
    fun roundTrip_relativeAbsoluteRelative_isIdempotent() {
        val store = newStore()
        val rel = "34020000001320000001/20260713/103045.mp4"
        assertEquals(rel, store.toRelative(store.toAbsolute(rel)))
    }
}

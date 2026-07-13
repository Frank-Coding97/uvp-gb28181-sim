package com.uvp.sim.recording

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 守护"多次录像列表始终只显示一条"回归 —— 2026-07-13 老板真机现场报告。
 *
 * 疑点回溯:
 *   - `IosRecordingService.finalizeWriterLocked` L636-639 是 `currentList + new` append 语义,
 *     mutex 内不该有 race
 *   - 但如果冷启动 [RecordingFileStore.loadIndex] 后 `_files.value = emptyList()`,
 *     后续 stop → finalize 就会 `[] + f2` 把磁盘上的 f1 覆盖掉
 *
 * 这个测直接打两条链:
 *   1. 反复 persist + load(模拟录像 → 退出 App → 重启 → 再录像 → 退出)不会丢历史
 *   2. 老 index.json(绝对路径)在 load → persist 一次后完成隐式迁移到相对路径,后续跨沙盒仍可用
 *
 * 注意:测试跑在真 iosSimulator 沙盒下,用 baseDir 直接读写文件。BeforeTest 清空 index.json
 * 避免相互污染,AfterTest 恢复。
 */
class RecordingFileStoreIndexRoundtripTest {

    private fun newStore() = RecordingFileStore(
        deviceIdSupplier = { "34020000001320000001" },
        timeZone = TimeZone.UTC,
    )

    private fun sampleFile(id: String, startMs: Long, path: String): RecordingFile =
        RecordingFile(
            id = id,
            startTimeMs = startMs,
            endTimeMs = startMs + 5_000,
            durationMs = 5_000,
            channelId = "34020000001320000001",
            filePath = path,
            sizeBytes = 1024,
            thumbnailPath = null,
            source = RecordSource.Manual,
            type = RecordType.Time,
        )

    @BeforeTest
    fun clearIndex() {
        val store = newStore()
        NSFileManager.defaultManager.removeItemAtPath(store.indexFilePath, error = null)
    }

    @AfterTest
    fun cleanup() {
        val store = newStore()
        NSFileManager.defaultManager.removeItemAtPath(store.indexFilePath, error = null)
    }

    /**
     * 核心回归:模拟"录像 → 退出 → 重启 → 再录像 → 退出",两次 persist 之间做一次 load
     * (这次 load 就是修 svc.load() 之前漏掉的那一步)。
     * 如果 finalize 前 currentList 拿到 emptyList,第二次 persist 会把 f1 冲掉,size=1。
     */
    @Test
    fun persist_load_persist_preservesHistory() = runTest {
        val store = newStore()

        // 第一次会话:录 f1 → persist [f1]
        val f1 = sampleFile("id-1111", 1_000_000L, store.toAbsolute("34020000001320000001/20260713/100000.mp4"))
        store.persistIndex(RecordingIndexFile(files = listOf(f1)))

        // 模拟 App 重启:从磁盘 load 拿到 [f1](rebaseToAbsolute)
        val loaded = store.loadIndex()
        assertEquals(1, loaded.size, "第一段录像应能被 load 回来")
        assertEquals("id-1111", loaded[0].id)

        // 第二次会话:在 loaded 基础上 append f2 → persist [f1, f2]
        val f2 = sampleFile("id-2222", 2_000_000L, store.toAbsolute("34020000001320000001/20260713/100100.mp4"))
        store.persistIndex(RecordingIndexFile(files = loaded + f2))

        // 第三次会话:再 load
        val finalLoaded = store.loadIndex()
        assertEquals(2, finalLoaded.size, "两段录像都必须存活")
        assertTrue(finalLoaded.any { it.id == "id-1111" }, "第一段不能被覆盖")
        assertTrue(finalLoaded.any { it.id == "id-2222" }, "第二段必须存在")
    }

    /**
     * 老 index.json 里存的是绝对路径(v1.1 早期实现),loadIndex 用 /recordings/ fallback
     * 兜底 → rebaseToAbsolute 得当前 baseDir 拼的新绝对路径。
     * 第二次 persist 后磁盘里存的是相对路径,后续跨沙盒仍能 rebase。
     */
    @Test
    fun loadIndex_migratesOldAbsolutePathsOnPersist() = runTest {
        val store = newStore()

        // 手写一个老风格 index.json(绝对路径,沙盒 UUID 已废)
        val staleAbs =
            "/var/mobile/Containers/Data/Application/OLDUUID-1234/Documents/recordings/34020000001320000001/20260713/090000.mp4"
        val oldFile = sampleFile("id-old", 500_000L, staleAbs)
        val oldJson = RecordingIndex.encode(RecordingIndexFile(files = listOf(oldFile)))
        writeStringToFile(store.indexFilePath, oldJson)

        // load:应该走 /recordings/ fallback → 重新拼当前沙盒的绝对路径
        val loaded = store.loadIndex()
        assertEquals(1, loaded.size)
        assertNotNull(loaded[0].filePath)
        assertTrue(loaded[0].filePath.startsWith("/"), "load 出的 filePath 必须是绝对路径")
        assertTrue(
            loaded[0].filePath.endsWith("34020000001320000001/20260713/090000.mp4"),
            "尾部相对段应被保留",
        )
        assertTrue(
            !loaded[0].filePath.contains("OLDUUID-1234"),
            "老沙盒 UUID 不该出现在 rebase 后的路径里 —— 实际=${loaded[0].filePath}",
        )

        // 再 persist 一次,新加一段 —— 关键校验点:老段没被丢弃,而且都能被下次 load 回来
        val f2 = sampleFile("id-new", 600_000L, store.toAbsolute("34020000001320000001/20260713/091000.mp4"))
        store.persistIndex(RecordingIndexFile(files = loaded + f2))

        val reloaded = store.loadIndex()
        assertEquals(2, reloaded.size, "老段迁移后新段追加,两段都要在")
        assertTrue(reloaded.any { it.id == "id-old" })
        assertTrue(reloaded.any { it.id == "id-new" })
    }

    /**
     * 幂等性:同一个 RecordingIndexFile 反复 persist/load 无限次,内容不漂移。
     * 这个不是老板报告的具体 bug,但如果坏了会引出下一个"数据静默膨胀 / 字段丢失"的问题,
     * 顺手守起来。
     */
    @Test
    fun persist_load_isIdempotentAcrossManyCycles() = runTest {
        val store = newStore()
        val f1 = sampleFile("id-a", 100L, store.toAbsolute("d/2026/1.mp4"))
        val f2 = sampleFile("id-b", 200L, store.toAbsolute("d/2026/2.mp4"))
        val f3 = sampleFile("id-c", 300L, store.toAbsolute("d/2026/3.mp4"))

        var current = listOf(f1, f2, f3)
        repeat(5) {
            store.persistIndex(RecordingIndexFile(files = current))
            current = store.loadIndex()
        }
        assertEquals(3, current.size)
        assertEquals(listOf("id-a", "id-b", "id-c"), current.map { it.id })
        assertEquals(f1.startTimeMs, current[0].startTimeMs)
        assertEquals(f1.durationMs, current[0].durationMs)
    }

    private fun writeStringToFile(path: String, content: String) {
        (content as NSString).writeToFile(
            path = path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
    }
}

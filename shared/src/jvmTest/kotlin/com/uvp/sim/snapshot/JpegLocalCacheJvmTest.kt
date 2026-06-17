package com.uvp.sim.snapshot

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * JVM 端真实写盘单测。Android actual 在真机回归(T6)。
 */
class JpegLocalCacheJvmTest {

    private lateinit var tmpRoot: File
    private lateinit var cache: JpegLocalCache

    @BeforeTest
    fun setup() {
        tmpRoot = createTempDir()
        cache = JpegLocalCache.forRoot(tmpRoot)
    }

    @AfterTest
    fun cleanup() {
        tmpRoot.deleteRecursively()
    }

    private fun createTempDir(): File {
        val f = File.createTempFile("uvp-snap-test", "")
        f.delete()
        f.mkdirs()
        return f
    }

    private fun mockOldFile(name: String, sizeBytes: Int, agedMs: Long): File {
        val sub = File(tmpRoot, "2026-06-10").apply { mkdirs() }
        val f = File(sub, name)
        f.writeBytes(ByteArray(sizeBytes))
        f.setLastModified(System.currentTimeMillis() - agedMs)
        return f
    }

    // T4.1
    @Test
    fun write_creates_file_with_correct_bytes() = runTest {
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42)
        val path = cache.write("snap_001", payload)
        val file = File(path)
        assertTrue(file.exists(), "file must exist")
        assertContentEquals(payload, file.readBytes())
    }

    // T4.2
    @Test
    fun write_overwrites_existing_with_same_id() = runTest {
        val first = byteArrayOf(0x01)
        val second = byteArrayOf(0x02, 0x03)
        cache.write("dup", first)
        val path = cache.write("dup", second)
        assertContentEquals(second, File(path).readBytes())
    }

    // T4.3
    @Test
    fun gc_removes_files_older_than_seven_days() = runTest {
        val eightDays = 8L * 24 * 60 * 60 * 1000
        val old = mockOldFile("old.jpg", 100, eightDays)
        cache.gc()
        assertFalse(old.exists())
    }

    // T4.4
    @Test
    fun gc_evicts_oldest_when_total_exceeds_100mb() = runTest {
        // 写两个大文件(模拟,每个 60MB)+ 一个 5MB,共 125MB
        // 都在 7 天内,所以仅 100MB 上限触发淘汰最旧
        val sub = File(tmpRoot, "2026-06-15").apply { mkdirs() }
        val sizeBig = 60 * 1024 * 1024  // 60MB
        val sizeSmall = 5 * 1024 * 1024  // 5MB
        val oldest = File(sub, "oldest.jpg").also {
            it.writeBytes(ByteArray(sizeBig))
            it.setLastModified(System.currentTimeMillis() - 1_000_000)
        }
        val mid = File(sub, "mid.jpg").also {
            it.writeBytes(ByteArray(sizeBig))
            it.setLastModified(System.currentTimeMillis() - 500_000)
        }
        val newest = File(sub, "newest.jpg").also {
            it.writeBytes(ByteArray(sizeSmall))
            it.setLastModified(System.currentTimeMillis())
        }
        cache.gc()
        // 总量 125MB > 100MB,淘汰 oldest 后 65MB ≤ 100MB,停
        assertFalse(oldest.exists(), "oldest must be evicted")
        assertTrue(mid.exists(), "mid kept (under cap after eviction)")
        assertTrue(newest.exists(), "newest kept")
    }

    // T4.5
    @Test
    fun gc_keeps_files_under_seven_days_when_under_cap() = runTest {
        val sub = File(tmpRoot, "2026-06-16").apply { mkdirs() }
        val recent = File(sub, "recent.jpg").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
            setLastModified(System.currentTimeMillis() - 1000)
        }
        cache.gc()
        assertTrue(recent.exists())
    }

    // 额外:write 路径以 .jpg 结尾
    @Test
    fun write_path_ends_with_jpg() = runTest {
        val path = cache.write("snap_xyz", byteArrayOf(0))
        assertTrue(path.endsWith(".jpg"))
    }

    // 额外:write 后路径含 SnapShotID
    @Test
    fun write_path_contains_snapshot_id() = runTest {
        val path = cache.write("snap_42", byteArrayOf(0))
        assertTrue(path.contains("snap_42"))
    }
}

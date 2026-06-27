package com.uvp.sim.observability

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SystemLoggerTest {

    @BeforeTest
    fun setup() = SystemLogger.resetForTest()

    @AfterTest
    fun teardown() = SystemLogger.resetForTest()

    @Test fun emitProducesOneEntry() = runTest {
        SystemLogger.bindScope(this)
        SystemLogger.emit(LogLevel.Info, LogTag.Network, "connected")
        testScheduler.advanceUntilIdle()
        val snap = SystemLogger.snapshot
        assertEquals(1, snap.size)
        assertEquals("connected", snap[0].message)
        assertEquals(LogLevel.Info, snap[0].level)
        assertEquals(LogTag.Network, snap[0].tag)
        SystemLogger.shutdownForTest()
    }

    @Test fun sanitizesPasswordAssignment() = runTest {
        SystemLogger.bindScope(this)
        SystemLogger.emit(LogLevel.Info, LogTag.User, "login password=abc123 ok")
        testScheduler.advanceUntilIdle()
        val msg = SystemLogger.snapshot[0].message
        assertTrue("password=****" in msg.lowercase(), "expected redaction, got: $msg")
        assertTrue("abc123" !in msg, "secret leaked: $msg")
        SystemLogger.shutdownForTest()
    }

    @Test fun sanitizesAuthorizationHeader() = runTest {
        SystemLogger.bindScope(this)
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "header Authorization: Bearer xxx.yyy.zzz tail"
        )
        testScheduler.advanceUntilIdle()
        val msg = SystemLogger.snapshot[0].message
        // M-7 (audit §3) — 整个 Authorization 头收敛成 <redacted>,不再走 kv 风格
        assertTrue("Authorization: <redacted>" in msg, "expected redaction, got: $msg")
        assertTrue("Bearer xxx.yyy.zzz" !in msg, "token leaked: $msg")
        SystemLogger.shutdownForTest()
    }

    @Test fun burstStaysWithinBufferCapacity() = runTest {
        SystemLogger.bindScope(this)
        repeat(2000) {
            SystemLogger.emit(LogLevel.Debug, LogTag.Network, "burst $it")
        }
        testScheduler.advanceUntilIdle()
        val snap = SystemLogger.snapshot
        assertTrue(snap.size <= 1000, "buffer over capacity: ${snap.size}")
        assertTrue(snap.isNotEmpty(), "buffer empty after burst")
        SystemLogger.shutdownForTest()
    }

    @Test fun seqIsMonotonic() = runTest {
        SystemLogger.bindScope(this)
        repeat(50) { SystemLogger.emit(LogLevel.Info, LogTag.Network, "n$it") }
        testScheduler.advanceUntilIdle()
        val seqs = SystemLogger.snapshot.map { it.seq }
        assertEquals(seqs.sorted(), seqs, "seq not monotonic: $seqs")
        assertEquals(seqs.distinct(), seqs, "seq not unique: $seqs")
        SystemLogger.shutdownForTest()
    }

    @Test fun clearReplacesBufferWithSingleMarker() = runTest {
        SystemLogger.bindScope(this)
        repeat(5) { SystemLogger.emit(LogLevel.Info, LogTag.Network, "old$it") }
        testScheduler.advanceUntilIdle()
        SystemLogger.clear()
        testScheduler.advanceUntilIdle()
        val snap = SystemLogger.snapshot
        assertEquals(1, snap.size, "after clear only marker should remain")
        assertEquals("日志已清除", snap[0].message)
        SystemLogger.shutdownForTest()
    }

    @Test fun emitAfterClearAccumulatesNormally() = runTest {
        SystemLogger.bindScope(this)
        SystemLogger.emit(LogLevel.Info, LogTag.Network, "before")
        SystemLogger.clear()
        SystemLogger.emit(LogLevel.Info, LogTag.Network, "after")
        testScheduler.advanceUntilIdle()
        val snap = SystemLogger.snapshot
        assertEquals(2, snap.size, "expected marker + post-clear emit")
        assertEquals("日志已清除", snap[0].message)
        assertEquals("after", snap[1].message)
        SystemLogger.shutdownForTest()
    }
}

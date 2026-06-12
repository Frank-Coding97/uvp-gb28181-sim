package com.uvp.sim.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class SystemLogBufferTest {

    private fun log(seq: Long, msg: String) = SystemLog(
        seq = seq,
        timestampMs = seq,
        sessionId = 1,
        level = LogLevel.Info,
        tag = LogTag.Network,
        message = msg
    )

    @Test fun keepsLastNWhenCapacityExceeded() {
        val buf = SystemLogBuffer(capacity = 3)
        repeat(5) { buf.add(log(it.toLong(), "m$it")) }
        val snap = buf.snapshot()
        assertEquals(3, snap.size)
        assertEquals(listOf("m2", "m3", "m4"), snap.map { it.message })
    }

    @Test fun fillsToCapacityWithoutLoss() {
        val buf = SystemLogBuffer(capacity = 1000)
        repeat(1000) { buf.add(log(it.toLong(), "m$it")) }
        assertEquals(1000, buf.snapshot().size)
    }

    @Test fun dropsOldestWhenOverCapacity() {
        val buf = SystemLogBuffer(capacity = 1000)
        repeat(1001) { buf.add(log(it.toLong(), "m$it")) }
        val snap = buf.snapshot()
        assertEquals(1000, snap.size)
        assertEquals("m1", snap.first().message)
        assertEquals("m1000", snap.last().message)
    }

    @Test fun snapshotIsIsolatedFromInternalState() {
        val buf = SystemLogBuffer(capacity = 10)
        buf.add(log(0, "first"))
        val snap = buf.snapshot()
        buf.add(log(1, "second"))
        // 第一次 snapshot 不应反映之后的添加
        assertEquals(1, snap.size)
        assertEquals("first", snap[0].message)
        // 拿到的不是同一个内部 list 引用
        val snap2 = buf.snapshot()
        assertNotSame(snap, snap2)
        assertEquals(2, snap2.size)
    }
}

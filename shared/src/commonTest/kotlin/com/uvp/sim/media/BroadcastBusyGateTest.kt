package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * T-E2-1:BroadcastBusyGate expect object 契约测试。
 *
 * expect object 无法在 commonTest 里 swap 出 fake actual(K/N 限制),这里只验通用契约:
 *   1. isBusy() 是纯函数,可反复调,不 throw
 *   2. busyReason() 与 isBusy() 语义一致:busy 时非 null,idle 时 null
 *
 * iOS 采集活跃时仍应放行的回归覆盖走 iosTest 的 [BroadcastBusyGateIosTest]；
 * Router 侧 future platform busy → ERROR 分支覆盖走 [ManscdpRouterBroadcastBusyTest]。
 *
 * 在 JVM target 上跑时,BroadcastBusyGate.jvm.kt 常量 false,契约验证 idle 分支。
 */
class BroadcastBusyGateTest {

    @Test
    fun isBusy_returns_consistent_boolean() {
        val a = BroadcastBusyGate.isBusy()
        val b = BroadcastBusyGate.isBusy()
        assertEquals(a, b, "isBusy() must be stable across successive calls (no I/O)")
    }

    @Test
    fun busyReason_matches_isBusy_semantic() {
        if (BroadcastBusyGate.isBusy()) {
            assertNotNull(BroadcastBusyGate.busyReason(), "busy 时 reason 应非 null")
        } else {
            assertNull(BroadcastBusyGate.busyReason(), "idle 时 reason 应为 null")
        }
    }

    @Test
    fun default_platform_is_idle() {
        // 当前所有平台均不以本地采集状态阻断广播。
        assertFalse(BroadcastBusyGate.isBusy(), "默认平台 gate 应 idle")
    }
}

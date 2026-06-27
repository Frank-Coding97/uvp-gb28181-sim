package com.uvp.sim.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M-6 (audit §3) — 服务器 IP 白名单单元测。验证 [ServerAllowList] 三类语义:
 *  - 空 list = 不强制(默认兼容);
 *  - 非空 + 命中 = 通过;
 *  - 非空 + 未命中 = 拒绝 + 抛 [ServerNotAllowedException]
 */
class AllowListEnforcementTest {

    @Test
    fun emptyAllowListNeverRefuses() {
        assertTrue(ServerAllowList.check("10.0.0.99", emptyList()))
        ServerAllowList.enforce("any-host", emptyList())  // 不应抛
    }

    @Test
    fun allowListedHostPasses() {
        val list = listOf("10.0.0.1", "10.0.0.2")
        assertTrue(ServerAllowList.check("10.0.0.2", list))
        ServerAllowList.enforce("10.0.0.2", list)
    }

    @Test
    fun nonAllowListedHostRefused() {
        val list = listOf("10.0.0.1")
        assertFalse(ServerAllowList.check("10.0.0.99", list))
    }

    @Test
    fun enforceThrowsServerNotAllowedException() {
        val list = listOf("10.0.0.1")
        val ex = assertFails {
            ServerAllowList.enforce("192.168.99.1", list)
        }
        if (ex !is ServerNotAllowedException) {
            fail("应抛 ServerNotAllowedException,实际 ${ex::class.simpleName}")
        }
        assertEquals("192.168.99.1", ex.host)
        assertEquals(list, ex.allowList)
    }

    @Test
    fun exactStringMatchOnly_noCidrSupport() {
        // v1 不支持 CIDR;'10.0.0.0/24' 字面量不会自动匹配 '10.0.0.5'
        assertFalse(
            ServerAllowList.check("10.0.0.5", listOf("10.0.0.0/24")),
            "v1 只字面量匹配,不解析 CIDR"
        )
    }
}

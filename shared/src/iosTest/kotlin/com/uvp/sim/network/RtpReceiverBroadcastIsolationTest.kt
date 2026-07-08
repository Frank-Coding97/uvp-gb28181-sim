package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-E3-0 spike:证明 broadcast RTP receiver 与推流 receiver 可以在同一 iOS app 内共存。
 *
 * 验:
 *   - 两个独立 RtpReceiver 实例都能 bind UDP 拿到不同的本地端口
 *   - 关闭一个不影响另一个的 localPort 状态
 */
class RtpReceiverBroadcastIsolationTest {

    @Test
    fun two_udp_receivers_bind_to_different_ports() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = RtpReceiver(parentScope = scope, expectedSourceHost = null)
        val b = RtpReceiver(parentScope = scope, expectedSourceHost = null)
        try {
            val portA = a.bind(RtpMode.UDP)
            val portB = b.bind(RtpMode.UDP)
            assertTrue(portA > 0, "receiver A should get a valid bound UDP port, got $portA")
            assertTrue(portB > 0, "receiver B should get a valid bound UDP port, got $portB")
            assertNotEquals(portA, portB, "两个 receiver 应绑到不同端口(隔离)")
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun closing_one_receiver_does_not_break_another() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = RtpReceiver(parentScope = scope, expectedSourceHost = null)
        val b = RtpReceiver(parentScope = scope, expectedSourceHost = null)
        try {
            a.bind(RtpMode.UDP)
            val portB = b.bind(RtpMode.UDP)
            a.close()
            // b 端口应仍报告(未 close),验隔离
            assertTrue(b.localPort > 0)
            assertTrue(b.localPort == portB)
        } finally {
            b.close()
        }
    }
}

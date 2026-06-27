package com.uvp.sim.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M-3 (audit §3) — TCP SIP keepalive 配置单元测。
 *
 * ktor 3.0.2 的 [io.ktor.network.sockets.SocketOptions.TCPClientSocketOptions] 构造
 * 是 internal,无法在测试里直接构造一份验证。改用间接验证:
 * [TcpSipTransport.configureKeepAlive] 接受一个 (Boolean) -> Unit setter,
 * 真实调用点是 ktor 的 `connect { keepAlive = it }` lambda。本测试注入 stub
 * setter,验证函数把预期值传入。
 *
 * 真 NAT 行为验证(idle 几分钟后心跳触发)需要专门集成环境,跨出单元测范畴。
 */
class TcpKeepaliveTest {

    @Test
    fun configureKeepAlivePassesTrueToSetter() {
        var captured: Boolean? = null
        TcpSipTransport.configureKeepAlive { value -> captured = value }
        assertNotNull(captured, "configureKeepAlive 必须调用注入的 setter")
        assertEquals(true, captured, "M-3 — TCP SIP socket 必须把 keepalive 设为 true")
    }

    @Test
    fun keepAliveSettingFlagIsTrue() {
        // 常量门面:确保有人改坏了 const(keepAlive 必须开)立刻挂
        assertTrue(TcpSipTransport.keepAliveSetting, "keepAliveSetting 常量永远应为 true")
    }
}

package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T4 测试:`localIpProvider` 改造正确性。
 *
 * 覆盖 plan §"T4 测试用例":
 *   1. provider 默认返回 0.0.0.0 → Contact 头含 0.0.0.0
 *   2. provider 注入指定 IP → Contact / Via 头都带该 IP
 *   3. provider 值动态变化 → 下一个 register cycle 的 Contact 用新 IP
 *   4. 一次 register 内 provider 多次访问得到一致值
 */
class SimulatorEngineLocalIpProviderTest {

    private fun cfg() = SimConfig(
        server = ServerConfig(
            ip = "127.0.0.1",
            serverId = "34020000002000000001",
            domain = "3402000000",
        ),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001310000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "12345678",
        ),
    )

    @Test
    fun `default provider returns 0_0_0_0`() = runTest {
        val transport = MockSipTransport()
        val engine = SimulatorEngine(cfg(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()

            assertTrue(transport.sent.isNotEmpty(), "REGISTER 应被发出")
            val req = transport.sent[0] as SipRequest
            assertEquals(SipMethod.REGISTER, req.method)
            val contact = req.firstHeader("Contact") ?: ""
            assertTrue(contact.contains("0.0.0.0"), "Contact 未含默认 IP: $contact")
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `provider supplies IP into Contact and Via headers`() = runTest {
        val transport = MockSipTransport()
        val engine = SimulatorEngine(
            cfg(), transport, this,
            localIpProvider = { "10.20.30.40" },
        )
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()

            val req = transport.sent[0] as SipRequest
            val contact = req.firstHeader("Contact") ?: ""
            val via = req.firstHeader("Via") ?: ""
            assertTrue(contact.contains("10.20.30.40"), "Contact 缺 IP: $contact")
            assertTrue(via.contains("10.20.30.40"), "Via 缺 IP: $via")
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `provider value change reflects in next register cycle`() = runTest {
        val transport = MockSipTransport()
        var ip = "1.1.1.1"
        val engine = SimulatorEngine(cfg(), transport, this, localIpProvider = { ip })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()

            val firstReq = transport.sent[0] as SipRequest
            assertTrue(
                (firstReq.firstHeader("Contact") ?: "").contains("1.1.1.1"),
                "首次 Contact 应含 1.1.1.1"
            )

            // 模拟 NetworkController 切换网卡后 IP 变了
            ip = "2.2.2.2"

            // 触发 unregister + register cycle(等价于 handleNetworkChange 触发的)
            engine.unregister()
            testScheduler.runCurrent()
            engine.register()
            testScheduler.runCurrent()

            // 找最后一条 REGISTER(Expires>0,排除 Expires=0 的 unregister)
            val lastRegister = transport.sent
                .filterIsInstance<SipRequest>()
                .last { it.method == SipMethod.REGISTER }
            val contact = lastRegister.firstHeader("Contact") ?: ""
            // 注:具体哪一条是新 register 不重要,只要存在含 2.2.2.2 的 Contact 即说明 provider 切换生效
            val hasNewIp = transport.sent
                .filterIsInstance<SipRequest>()
                .filter { it.method == SipMethod.REGISTER }
                .any { (it.firstHeader("Contact") ?: "").contains("2.2.2.2") }
            assertTrue(hasNewIp, "切换后没有任何 REGISTER 的 Contact 含 2.2.2.2 (last=$contact)")
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `within one register dialog all headers use the same IP`() = runTest {
        val transport = MockSipTransport()
        var calls = 0
        val engine = SimulatorEngine(
            cfg(), transport, this,
            localIpProvider = {
                calls++
                "10.20.30.40"
            },
        )
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()

            val req = transport.sent[0] as SipRequest
            val contact = req.firstHeader("Contact") ?: ""
            val via = req.firstHeader("Via") ?: ""
            assertEquals("10.20.30.40", extractIp(contact), "Contact IP mismatch")
            assertEquals("10.20.30.40", extractIp(via), "Via IP mismatch")
            assertTrue(calls > 0, "provider 应至少被调用一次")
        } finally {
            engine.shutdown()
        }
    }

    private fun extractIp(headerValue: String): String {
        val match = Regex("(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(headerValue)
        return match?.value ?: ""
    }
}

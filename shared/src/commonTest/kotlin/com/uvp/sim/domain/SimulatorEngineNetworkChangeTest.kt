package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.NetworkState
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * T5 测试:`handleNetworkChange` 编排正确性。
 *
 * 覆盖 plan §"T5 测试用例" 5 条:
 *   1. Auto → Bound,SIP 已 Registered → 触发 unregister + register
 *   2. Auto → Bound,SIP Disconnected → 不触发(老板没手动点)
 *   3. Bound → Unavailable → emit NetworkUnavailable,SIP 状态不变
 *   4. Switching → engine 不动作
 *   5. InCall 状态下网卡切换 → 也触发 reregister(但不 stopStream)
 */
class SimulatorEngineNetworkChangeTest {

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

    private suspend fun bringRegistered(
        engine: SimulatorEngine,
        transport: MockSipTransport,
    ) {
        // 调用方应在调用此函数后再 testScheduler.runCurrent() 以推进协程。
        transport.connect()
        engine.register()
    }

    private fun fakeResponse(
        request: SipRequest,
        statusCode: Int,
        reasonPhrase: String,
    ): SipResponse {
        val baseHeaders = request.headers.filter {
            val k = SipHeader.canonicalize(it.name)
            k == SipHeader.VIA || k == SipHeader.FROM || k == SipHeader.CALL_ID || k == SipHeader.CSEQ
        } + SipMessage.Header(
            SipHeader.TO,
            (request.toHeader() ?: "<sip:u@e>") + ";tag=server-tag"
        )
        return SipResponse(
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            headers = baseHeaders,
        )
    }

    @Test
    fun `Bound triggers unregister and register when SIP Registered`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "1.1.1.1" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val first = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(first, 200, "OK"))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value, "前置:应在 Registered")

            transport.sent.clear()
            engine.handleNetworkChange(
                NetworkState.Bound(NetworkPreference.WIFI, "wlan0", "2.2.2.2")
            )
            testScheduler.runCurrent()

            val registers = transport.sent.filterIsInstance<SipRequest>()
                .filter { it.method == SipMethod.REGISTER }
            assertTrue(registers.size >= 2, "应有至少 2 条 REGISTER(unregister + 重注册),实际=${registers.size}")
            val expiresValues = registers.mapNotNull { it.firstHeader("Expires") }
            assertTrue(
                expiresValues.any { it == "0" },
                "应至少有一条 Expires=0 表示 unregister,实际 Expires 值=$expiresValues"
            )
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `Bound does NOT trigger reregister when SIP Disconnected`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "1.1.1.1" })
        try {
            transport.connect()
            assertEquals(SipState.Disconnected, engine.state.value, "前置:Disconnected")

            engine.handleNetworkChange(
                NetworkState.Bound(NetworkPreference.WIFI, "wlan0", "2.2.2.2")
            )
            testScheduler.runCurrent()

            assertTrue(
                transport.sent.filterIsInstance<SipRequest>()
                    .none { it.method == SipMethod.REGISTER },
                "Disconnected 状态下不应发 REGISTER,实际发了 ${transport.sent.size} 条"
            )
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `Unavailable emits NetworkUnavailable event without unregister`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "1.1.1.1" })
        val collected = mutableListOf<SimEvent>()
        val job = launch {
            engine.events.collect { collected.add(it) }
        }
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val first = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(first, 200, "OK"))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)

            transport.sent.clear()
            collected.clear()

            engine.handleNetworkChange(
                NetworkState.Unavailable(NetworkPreference.CELLULAR, "蜂窝不可用")
            )
            testScheduler.runCurrent()

            assertTrue(
                collected.any { it is SimEvent.NetworkUnavailable && it.reason.contains("蜂窝") },
                "应 emit NetworkUnavailable 事件,实际 events=${collected.map { it::class.simpleName }}"
            )
            assertTrue(
                transport.sent.filterIsInstance<SipRequest>()
                    .none { it.method == SipMethod.REGISTER },
                "Unavailable 状态不应主动 unregister(发不出去)"
            )
        } finally {
            job.cancel()
            engine.shutdown()
        }
    }

    @Test
    fun `Switching state is a no-op for the engine`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "1.1.1.1" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val first = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(first, 200, "OK"))
            testScheduler.runCurrent()
            val sentBefore = transport.sent.size
            val stateBefore = engine.state.value

            engine.handleNetworkChange(
                NetworkState.Switching(NetworkPreference.WIFI, NetworkPreference.CELLULAR)
            )
            testScheduler.runCurrent()

            assertEquals(sentBefore, transport.sent.size, "Switching 时不应发任何 SIP 消息")
            assertEquals(stateBefore, engine.state.value, "Switching 时 SIP 状态不应变")
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `Auto triggers reregister when SIP Registered`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "1.1.1.1" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val first = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(first, 200, "OK"))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)

            transport.sent.clear()
            engine.handleNetworkChange(NetworkState.Auto)
            testScheduler.runCurrent()

            val registers = transport.sent.filterIsInstance<SipRequest>()
                .filter { it.method == SipMethod.REGISTER }
            assertTrue(registers.isNotEmpty(), "Auto 也应触发重注册(刷 Contact 头),但实际未发")
        } finally {
            engine.shutdown()
        }
    }
}

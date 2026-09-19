package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.ConnectionLostReason
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import com.uvp.sim.testing.TestEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GB/T 28181 §5.2 —— 传输层断链自愈的**引擎级闭环**验收(backlog F-4 的"验收闭环"一栏)。
 *
 * 要证的是这条链子整条通:
 *   TCP read loop 死 → 上报 ConnectionLost → 活跃流收尾 + 注册会话作废 → 退避
 *   → 重建 TCP → **真的重新发 REGISTER**
 *
 * 最后那一环是重点。退避表对不对([SipReconnectTest])、TCP 有没有真的重连
 * ([com.uvp.sim.network.TcpSipTransportReconnectTest])都是局部性质;
 * "重连成功但设备再也没注册过"这个故障恰好能同时满足那两个局部性质 ——
 * 过去它就是被 `RegistrationCoordinatorImpl.register()` 开头那句
 * "状态仍是 Registered 就 return" 的守卫挡住的,而界面上看起来一切正常。
 */
class SimulatorEngineReconnectTest {

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
            password = "test-password",
        ),
    )

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

    private suspend fun bringRegistered(
        engine: SimulatorEngine,
        transport: MockSipTransport,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) {
        transport.connect()
        engine.register()
        scheduler.runCurrent()
        val first = transport.sent.first() as SipRequest
        transport.deliver(fakeResponse(first, 200, "OK"))
        scheduler.runCurrent()
        assertEquals(SipState.Registered, engine.state.value, "前置条件:设备应已注册")
    }

    @Test
    fun `loss invalidates the registration session then auto-reconnects and re-registers`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "1.1.1.1" })
        try {
            bringRegistered(engine, transport, testScheduler)
            transport.sent.clear()

            transport.simulateConnectionLoss(ConnectionLostReason.PeerClosed, "对端 FIN")
            testScheduler.runCurrent()

            // ① 注册会话必须被作废:状态一旦还停在 Registered,
            //    RegistrationCoordinatorImpl.register() 开头的守卫会直接 return,
            //    后面 TCP 就算连上也不会有一条 REGISTER 出去。
            assertEquals(
                SipState.Disconnected,
                engine.state.value,
                "断链后注册会话应作废(否则 UI 会继续谎报\"设备已注册\")",
            )
            assertEquals(1, engine.reconnect.value?.attempt, "应立刻进入第 1 次重连")
            assertEquals(1_000L, engine.reconnect.value?.delayMs)
            assertEquals(ConnectionLostReason.PeerClosed, engine.reconnect.value?.reason)

            // ② 退避到期 → 重建连接 → 重新注册
            testScheduler.advanceTimeBy(1_000L)
            testScheduler.runCurrent()

            assertEquals(2, transport.connectCalls, "退避到期后应自动重建 TCP 连接")
            assertNull(engine.reconnect.value, "重连成功后\"正在重连\"标记应摘掉")
            val registers = transport.sent
                .filterIsInstance<SipRequest>()
                .filter { it.method == SipMethod.REGISTER }
            assertTrue(
                registers.isNotEmpty(),
                "重连后必须重新发 REGISTER —— 这是 F-4 的核心验收点," +
                    "实际发出的消息=${transport.sent.map { it::class.simpleName }}",
            )
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `reconnect attempt is surfaced to the UI via events`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "1.1.1.1" })
        val collected = mutableListOf<SimEvent>()
        val collector = launch { engine.events.collect { collected.add(it) } }
        try {
            bringRegistered(engine, transport, testScheduler)
            testScheduler.runCurrent()
            collected.clear()

            transport.simulateConnectionLoss()
            testScheduler.runCurrent()

            assertTrue(
                collected.any { it is SimEvent.ConnectionLost },
                "应广播断连事件,实际=${collected.map { it::class.simpleName }}",
            )
            assertTrue(
                collected.any { it is SimEvent.ReconnectScheduled && it.attempt == 1 && it.delayMs == 1_000L },
                "应广播重连排期(含退避时长),实际=${collected.map { it::class.simpleName }}",
            )

            testScheduler.advanceTimeBy(1_000L)
            testScheduler.runCurrent()

            assertTrue(
                collected.any { it is SimEvent.ReconnectSucceeded },
                "应广播重连成功,实际=${collected.map { it::class.simpleName }}",
            )
        } finally {
            collector.cancel()
            engine.shutdown()
        }
    }

    /**
     * 平台长时间不可达时的稳态:退避封顶 30 秒,但**永不放弃**。
     * 封顶值本身由纯函数测钉死,这里验的是"引擎级链路在连续失败下也不会自己收手"。
     */
    @Test
    fun `engine keeps retrying at the capped interval while the platform stays unreachable`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "1.1.1.1" })
        try {
            bringRegistered(engine, transport, testScheduler)

            transport.failConnectRemaining = 20
            transport.simulateConnectionLoss()
            testScheduler.runCurrent()

            // 跑过封顶点:1+2+4+8+16+30(封顶)= 61s,再走 3 拍封顶值
            repeat(8) {
                val attempt = engine.reconnect.value
                assertTrue(attempt != null, "平台不可达期间应始终保持\"正在重连\"")
                testScheduler.advanceTimeBy(attempt!!.delayMs)
                testScheduler.runCurrent()
            }

            assertEquals(
                30_000L,
                engine.reconnect.value?.delayMs,
                "长时间不可达后应稳定在封顶间隔,而不是继续翻倍或放弃",
            )
            assertEquals(9, transport.connectCalls, "1 次初始建连 + 8 次失败的重建尝试")

            // 平台恢复:下一次退避到期就应连上并重新注册
            transport.failConnectRemaining = 0
            testScheduler.advanceTimeBy(30_000L)
            testScheduler.runCurrent()

            assertEquals(10, transport.connectCalls)
            assertNull(engine.reconnect.value, "平台恢复后应结束重连态")
            assertTrue(
                transport.sent.filterIsInstance<SipRequest>().any { it.method == SipMethod.REGISTER },
                "平台恢复后应重新注册",
            )
        } finally {
            engine.shutdown()
        }
    }
}

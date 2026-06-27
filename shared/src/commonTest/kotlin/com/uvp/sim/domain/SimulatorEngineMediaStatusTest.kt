package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.MediaStatusNotify
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * C2 — SimulatorEngine.triggerMediaStatusAbnormal(notifyType) 演示触发口
 * (M5 平台兼容性补漏 batch1, 矩阵 7.9).
 *
 * 行为:
 *   - 校验 notifyType ∈ {122, 123},否则忽略
 *   - fan-out:MESSAGE 给注册中心 + (若有) NOTIFY 给已订阅 Alarm 的平台
 *   - emit MediaStatusSent(notifyType, subscriberCount)
 *   - 未注册时不发(emit TransportError)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorEngineMediaStatusTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(ip = "192.168.1.100", port = 5060,
            serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "wvp2025!!!"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915)
    )

    private fun fakeRegister200(req: SipRequest): SipResponse {
        val headers = req.headers.filter {
            val c = SipHeader.canonicalize(it.name)
            c == SipHeader.VIA || c == SipHeader.FROM ||
            c == SipHeader.CALL_ID || c == SipHeader.CSEQ
        } + SipMessage.Header(SipHeader.TO,
            (req.toHeader() ?: "<sip:u@e>") + ";tag=server-tag")
        return SipResponse(statusCode = 200, reasonPhrase = "OK", headers = headers)
    }

    private suspend fun registerEngine(
        transport: MockSipTransport,
        engine: SimulatorEngine,
        scope: kotlinx.coroutines.test.TestScope
    ) {
        engine.register()
        scope.testScheduler.runCurrent()
        val regReq = transport.sent.filterIsInstance<SipRequest>()
            .first { it.method == SipMethod.REGISTER }
        transport.deliver(fakeRegister200(regReq))
        scope.testScheduler.runCurrent()
    }

    private fun alarmSubscribe(callId: String): SipRequest {
        val headers = listOf(
            SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-alm-$callId"),
            SipMessage.Header(SipHeader.FROM,
                "<sip:34020000002000000001@3402000000>;tag=plat-$callId"),
            SipMessage.Header(SipHeader.TO,
                "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
            SipMessage.Header(SipHeader.EVENT, "Alarm"),
            SipMessage.Header(SipHeader.EXPIRES, "3600")
        )
        return SipRequest(
            method = SipMethod.SUBSCRIBE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = headers,
            body = "<Query><CmdType>Alarm</CmdType><SN>1</SN></Query>".encodeToByteArray()
        )
    }

    private fun mediaStatusBodies(transport: MockSipTransport): List<String> =
        transport.sent.filterIsInstance<SipRequest>()
            .filter {
                it.method == SipMethod.MESSAGE || it.method == SipMethod.NOTIFY
            }
            .map { it.body.decodeToString() }
            .filter { it.contains("<CmdType>MediaStatus</CmdType>") }

    @Test
    fun c2_t1_trigger122_emitsOneMessageWithNotifyType122() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        engine.triggerMediaStatusAbnormal(MediaStatusNotify.NOTIFY_TYPE_RECORDING_ABNORMAL)
        runCurrent()

        val bodies = mediaStatusBodies(transport)
        assertEquals(1, bodies.size, "无订阅时仅发 1 条 MESSAGE 给注册中心")
        assertTrue(bodies.first().contains("<NotifyType>122</NotifyType>"))
        engine.shutdown()
    }

    @Test
    fun c2_t2_trigger123_emitsNotifyType123() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        engine.triggerMediaStatusAbnormal(MediaStatusNotify.NOTIFY_TYPE_STORAGE_FULL)
        runCurrent()

        val bodies = mediaStatusBodies(transport)
        assertEquals(1, bodies.size)
        assertTrue(bodies.first().contains("<NotifyType>123</NotifyType>"))
        engine.shutdown()
    }

    @Test
    fun c2_t3_invalidNotifyTypeIsIgnored() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        engine.triggerMediaStatusAbnormal(999)
        runCurrent()

        val bodies = mediaStatusBodies(transport)
        assertEquals(0, bodies.size, "非法 NotifyType 应忽略,无 MediaStatus 出栈")
        engine.shutdown()
    }

    @Test
    fun c2_t4_alarmSubscribers_fanOutToBoth() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        // 加 2 个 Alarm 订阅
        transport.deliver(alarmSubscribe("alm-1@plat"))
        runCurrent()
        transport.deliver(alarmSubscribe("alm-2@plat"))
        runCurrent()
        transport.sent.clear()

        engine.triggerMediaStatusAbnormal(122)
        runCurrent()

        val msgBodies = transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.MESSAGE }
            .map { it.body.decodeToString() }
            .filter { it.contains("<CmdType>MediaStatus</CmdType>") }
        val notifyBodies = transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.NOTIFY }
            .map { it.body.decodeToString() }
            .filter { it.contains("<CmdType>MediaStatus</CmdType>") }
        assertEquals(1, msgBodies.size, "注册中心 MESSAGE 1 条")
        assertEquals(2, notifyBodies.size, "2 个 Alarm 订阅各收 1 条 NOTIFY")
        // 全部 body 都带 NotifyType=122
        (msgBodies + notifyBodies).forEach {
            assertTrue(it.contains("<NotifyType>122</NotifyType>"),
                "fan-out body 应均含 NotifyType=122: $it")
        }
        engine.shutdown()
    }

    @Test
    fun c2_t5_notRegisteredIsIgnored() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        // 不调 register
        engine.triggerMediaStatusAbnormal(122)
        runCurrent()

        val bodies = transport.sent.filterIsInstance<SipRequest>()
            .map { it.body.decodeToString() }
            .filter { it.contains("<CmdType>MediaStatus</CmdType>") }
        assertEquals(0, bodies.size, "未注册不应发 MediaStatus")
        engine.shutdown()
    }

    @Test
    fun c2_t6_emitsMediaStatusSent() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()

        val seen = mutableListOf<SimEvent.MediaStatusSent>()
        val collector = launch {
            engine.events.collect { if (it is SimEvent.MediaStatusSent) seen += it }
        }
        runCurrent()

        engine.triggerMediaStatusAbnormal(122)
        runCurrent()

        assertEquals(1, seen.size, "应 emit 一条 MediaStatusSent")
        assertEquals(122, seen.first().notifyType)
        assertEquals(0, seen.first().subscriberCount, "0 个 Alarm 订阅")
        collector.cancel()
        engine.shutdown()
    }
}

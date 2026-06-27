package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.AlarmPriority
import com.uvp.sim.gb28181.AlarmType
import com.uvp.sim.network.TransportType
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
 * T9 — SimulatorEngine.reportAlarm fan-out:MESSAGE 给注册中心 + NOTIFY 给订阅人。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorEngineAlarmTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(ip = "192.168.1.100", port = 5060, serverId = "34020000002000000001", domain = "3402000000"),
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

    private fun alarmSubscribeRequest(callId: String): SipRequest {
        val headers = listOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-alm-$callId"),
            SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=plat-$callId"),
            SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
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

    private suspend fun registerEngine(
        transport: MockSipTransport,
        engine: SimulatorEngine,
        scope: kotlinx.coroutines.test.TestScope
    ) {
        engine.register()
        scope.testScheduler.runCurrent()
        val regReq = transport.sent.filterIsInstance<SipRequest>().first { it.method == SipMethod.REGISTER }
        transport.deliver(fakeRegister200(regReq))
        scope.testScheduler.runCurrent()
    }

    private fun fakeRegister200(req: SipRequest): SipResponse {
        val headers = req.headers.filter {
            val c = SipHeader.canonicalize(it.name)
            c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.CALL_ID || c == SipHeader.CSEQ
        } + SipMessage.Header(SipHeader.TO, (req.toHeader() ?: "<sip:u@e>") + ";tag=server-tag")
        return SipResponse(statusCode = 200, reasonPhrase = "OK", headers = headers)
    }

    private fun payload() = AlarmPayload(
        deviceId = "34020000001340000001",
        priority = AlarmPriority.General,
        type = AlarmType.Other,
        description = "测试报警"
    )

    @Test
    fun reportAlarmWithoutSubscriberSendsOneMessageNoNotify() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        engine.reportAlarm(payload())
        runCurrent()

        val messages = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.MESSAGE }
        val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.NOTIFY }
        assertEquals(1, messages.size, "应发 1 条 MESSAGE 给注册中心")
        assertEquals(0, notifies.size, "无订阅时不发 NOTIFY")
        assertTrue(messages.first().body.decodeToString().contains("<CmdType>Alarm</CmdType>"))

        assertEquals(1, engine.alarmHistory.value.size)
        assertTrue(engine.deviceControlState.value.isAlarming)

        engine.shutdown()
    }

    @Test
    fun reportAlarmWithTwoSubscribersSendsMessagePlusTwoNotifies() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()

        transport.deliver(alarmSubscribeRequest("alm-1@plat"))
        runCurrent()
        transport.deliver(alarmSubscribeRequest("alm-2@plat"))
        runCurrent()
        transport.sent.clear()

        engine.reportAlarm(payload())
        runCurrent()

        val messages = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.MESSAGE }
        val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.NOTIFY }
        assertEquals(1, messages.size)
        assertEquals(2, notifies.size, "2 个 Alarm 订阅应各收 1 条 NOTIFY")
        // history 记录通知数
        assertEquals(2, engine.alarmHistory.value.last().notifiedSubscribers)

        engine.shutdown()
    }

    @Test
    fun reportAlarmEmitsAlarmFired() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()

        val fired = mutableListOf<SimEvent.AlarmFired>()
        val job = launch { engine.events.collect { if (it is SimEvent.AlarmFired) fired += it } }
        runCurrent()

        engine.reportAlarm(payload())
        runCurrent()

        assertEquals(1, fired.size)
        assertEquals(AlarmType.Other, fired.first().type)
        job.cancel()
        engine.shutdown()
    }

    @Test
    fun reportSnapshotStillUsesOldPathNotAlarmHistory() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        engine.reportSnapshot()
        runCurrent()

        // snapshot 走老路径,不进 alarmHistory,不切 isAlarming
        assertEquals(0, engine.alarmHistory.value.size)
        assertTrue(!engine.deviceControlState.value.isAlarming)

        engine.shutdown()
    }
}

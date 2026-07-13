package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * T10 — SimulatorEngine.handleAlarmCmd(平台反向复位)+ localResetAlarm(本地复位)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorEngineAlarmCmdTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(ip = "192.168.1.100", port = 5060, serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "test-password"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915)
    )

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

    private fun alarmCmdMessage(value: String): SipRequest {
        val body = """<?xml version="1.0"?>
<Control><CmdType>DeviceControl</CmdType><SN>1</SN>
<DeviceID>34020000001110000001</DeviceID><AlarmCmd>$value</AlarmCmd></Control>"""
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-cmd"),
                SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=plat-cmd"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, "cmd@plat"),
                SipMessage.Header(SipHeader.CSEQ, "2 MESSAGE")
            ),
            body = body.encodeToByteArray()
        )
    }

    private fun alarmSubscribeRequest(callId: String): SipRequest = SipRequest(
        method = SipMethod.SUBSCRIBE,
        requestUri = "sip:34020000001110000001@192.168.1.50:5060",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-alm"),
            SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=plat-alm"),
            SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
            SipMessage.Header(SipHeader.EVENT, "Alarm"),
            SipMessage.Header(SipHeader.EXPIRES, "3600")
        ),
        body = "<Query><CmdType>Alarm</CmdType><SN>1</SN></Query>".encodeToByteArray()
    )

    @Test
    fun alarmCmd0SendsOkAndResetsAndEmitsRemote() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        // 先制造报警中状态
        engine.reportAlarm(com.uvp.sim.gb28181.AlarmPayload(deviceId = "34020000001340000001"))
        runCurrent()
        assertTrue(engine.deviceControlState.value.isAlarming)
        transport.sent.clear()

        val resets = mutableListOf<SimEvent.AlarmReset>()
        val job = launch { engine.events.collect { if (it is SimEvent.AlarmReset) resets += it } }
        runCurrent()

        transport.deliver(alarmCmdMessage("0"))
        runCurrent()

        // 回 200 OK MESSAGE Response(handleMessage 顶部统一回)
        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertTrue(responses.any { it.statusCode == 200 })
        assertFalse(engine.deviceControlState.value.isAlarming)
        assertEquals(1, resets.size)
        assertTrue(resets.first().by is SimEvent.ResetSource.Remote)

        job.cancel()
        engine.shutdown()
    }

    @Test
    fun alarmCmd0WithSubscriberPushesResetNotify() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.deliver(alarmSubscribeRequest("alm-r@plat"))
        runCurrent()
        engine.reportAlarm(com.uvp.sim.gb28181.AlarmPayload(deviceId = "34020000001340000001"))
        runCurrent()
        transport.sent.clear()

        transport.deliver(alarmCmdMessage("0"))
        runCurrent()

        val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.NOTIFY }
        assertEquals(1, notifies.size, "复位应推 1 条 NOTIFY 给订阅人")
        assertTrue(notifies.first().body.decodeToString().contains("报警已复位"))

        engine.shutdown()
    }

    @Test
    fun alarmCmd1SendsOkButNoResetNoNotify() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.deliver(alarmSubscribeRequest("alm-g@plat"))
        runCurrent()
        engine.reportAlarm(com.uvp.sim.gb28181.AlarmPayload(deviceId = "34020000001340000001"))
        runCurrent()
        assertTrue(engine.deviceControlState.value.isAlarming)
        transport.sent.clear()

        transport.deliver(alarmCmdMessage("1"))
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertTrue(responses.any { it.statusCode == 200 })
        // 不切 isAlarming
        assertTrue(engine.deviceControlState.value.isAlarming)
        val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.NOTIFY }
        assertEquals(0, notifies.size)

        engine.shutdown()
    }

    @Test
    fun localResetAlarmDoesNotSendSip() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        engine.reportAlarm(com.uvp.sim.gb28181.AlarmPayload(deviceId = "34020000001340000001"))
        runCurrent()
        assertTrue(engine.deviceControlState.value.isAlarming)
        transport.sent.clear()

        val resets = mutableListOf<SimEvent.AlarmReset>()
        val job = launch { engine.events.collect { if (it is SimEvent.AlarmReset) resets += it } }
        runCurrent()

        engine.localResetAlarm()
        runCurrent()

        assertFalse(engine.deviceControlState.value.isAlarming)
        assertEquals(1, resets.size)
        assertTrue(resets.first().by is SimEvent.ResetSource.Local)
        // 不走 SIP — 出站 0 条
        assertEquals(0, transport.sent.size)

        job.cancel()
        engine.shutdown()
    }
}

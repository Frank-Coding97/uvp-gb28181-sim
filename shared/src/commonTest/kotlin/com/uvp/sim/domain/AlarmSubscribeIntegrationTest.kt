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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * T11 — Event:Alarm 订阅生命周期(activate / 不发 initial / refresh / cancel / 自然过期)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmSubscribeIntegrationTest {

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

    private fun alarmSub(callId: String, expires: Int? = 3600): SipRequest {
        val headers = mutableListOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-$callId"),
            SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=plat-$callId"),
            SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
            SipMessage.Header(SipHeader.EVENT, "Alarm")
        )
        if (expires != null) headers += SipMessage.Header(SipHeader.EXPIRES, expires.toString())
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

    @Test
    fun alarmSubscribeReturns200NoInitialNotifyEmitsSubscribed() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        val subs = mutableListOf<SimEvent.AlarmSubscribed>()
        val job = launch { engine.events.collect { if (it is SimEvent.AlarmSubscribed) subs += it } }
        runCurrent()

        transport.deliver(alarmSub("a1@plat"))
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertTrue(responses.any { it.statusCode == 200 }, "应回 200 OK")
        // Alarm 不发 initial NOTIFY
        val notifies = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(0, notifies, "Alarm 订阅不发 initial NOTIFY")
        // 订阅快照 active
        val snap = engine.subscriptions.value["Alarm"]
        assertTrue(snap != null && snap.active)
        assertEquals(3600, snap!!.expiresSeconds)
        assertEquals(1, subs.size)

        job.cancel()
        engine.shutdown()
    }

    @Test
    fun alarmSubscribeRefreshKeepsSingleDialogNoNotify() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.deliver(alarmSub("ref@plat"))
        runCurrent()
        transport.sent.clear()

        transport.deliver(alarmSub("ref@plat", expires = 7200))
        runCurrent()

        val notifies = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(0, notifies, "续订不发 NOTIFY")
        // dialog 数仍 1(快照仍 active)
        assertEquals(1, engine.subscriptions.value.values.count { it.active })

        engine.shutdown()
    }

    @Test
    fun alarmSubscribeExpires0CancelsImmediately() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.deliver(alarmSub("cancel@plat"))
        runCurrent()
        assertTrue(engine.subscriptions.value["Alarm"]?.active == true)

        val expired = mutableListOf<SimEvent.AlarmSubscriptionExpired>()
        val job = launch { engine.events.collect { if (it is SimEvent.AlarmSubscriptionExpired) expired += it } }
        runCurrent()

        transport.deliver(alarmSub("cancel@plat", expires = 0))
        runCurrent()

        assertEquals(null, engine.subscriptions.value["Alarm"])
        assertEquals(1, expired.size)

        job.cancel()
        engine.shutdown()
    }

    @Test
    fun alarmSubscribeNaturalExpiryEmitsExpired() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()

        val expired = mutableListOf<SimEvent.AlarmSubscriptionExpired>()
        val job = launch { engine.events.collect { if (it is SimEvent.AlarmSubscriptionExpired) expired += it } }
        runCurrent()

        transport.deliver(alarmSub("nat@plat", expires = 3))
        runCurrent()
        assertTrue(engine.subscriptions.value["Alarm"]?.active == true)

        advanceTimeBy(3_100)
        runCurrent()

        assertEquals(null, engine.subscriptions.value["Alarm"])
        assertEquals(1, expired.size)

        job.cancel()
        engine.shutdown()
    }
}

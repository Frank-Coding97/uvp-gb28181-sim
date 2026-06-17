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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubscribeIntegrationTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.1.100",
            port = 5060,
            serverId = "34020000002000000001",
            domain = "3402000000"
        ),
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

    private fun subscribeRequest(
        expires: Int = 10,
        interval: Int = 3,
        callId: String = "sub-call@platform"
    ): SipRequest {
        val body = """<?xml version="1.0"?>
<Query>
<CmdType>MobilePosition</CmdType>
<SN>1</SN>
<DeviceID>34020000001110000001</DeviceID>
<Interval>$interval</Interval>
</Query>""".encodeToByteArray()
        return SipRequest(
            method = SipMethod.SUBSCRIBE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-plat1"),
                SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=plat-tag"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
                SipMessage.Header(SipHeader.EVENT, "presence"),
                SipMessage.Header(SipHeader.EXPIRES, expires.toString())
            ),
            body = body
        )
    }

    private suspend fun registerEngine(transport: MockSipTransport, engine: SimulatorEngine) {
        engine.register()
        val regReq = transport.sent.filterIsInstance<SipRequest>().first { it.method == SipMethod.REGISTER }
        transport.deliver(fakeRegister200(regReq))
    }

    @Test
    fun subscribeResponds200AndSendsInitialNotify() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(subscribeRequest(expires = 10, interval = 3))
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>()
        val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.NOTIFY }

        assertTrue(responses.any { it.statusCode == 200 }, "Expected 200 OK for SUBSCRIBE")
        val ok200 = responses.first { it.statusCode == 200 }
        assertNotNull(ok200.firstHeader(SipHeader.SUBSCRIPTION_STATE))

        assertTrue(notifies.isNotEmpty(), "Expected initial NOTIFY")
        val body = notifies.first().body.decodeToString()
        assertTrue(body.contains("<CmdType>MobilePosition</CmdType>"))

        engine.shutdown()
    }

    @Test
    fun periodicNotifyFiresAfterInterval() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(subscribeRequest(interval = 3, expires = 10))
        runCurrent()

        val initialNotifies = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(1, initialNotifies)

        advanceTimeBy(3_001)
        val afterTick = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(2, afterTick)

        engine.shutdown()
    }

    @Test
    fun cancelSubscribeStopsNotify() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()

        transport.deliver(subscribeRequest(interval = 3, expires = 10, callId = "cancel@plat"))
        runCurrent()
        transport.sent.clear()

        transport.deliver(subscribeRequest(expires = 0, callId = "cancel@plat"))
        runCurrent()

        val notifiesAfterCancel = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        advanceTimeBy(6_000)
        val notifiesLater = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(notifiesAfterCancel, notifiesLater)
        assertTrue(engine.subscriptions.value.isEmpty())

        engine.shutdown()
    }

    @Test
    fun subscriptionsFlowReflectsActiveState() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()

        assertTrue(engine.subscriptions.value.isEmpty())

        transport.deliver(subscribeRequest(expires = 10))
        runCurrent()

        val snap = engine.subscriptions.value["MobilePosition"]
        assertNotNull(snap)
        assertTrue(snap.active)

        engine.shutdown()
    }

    private fun fakeRegister200(req: SipRequest): SipResponse {
        val headers = req.headers.filter {
            val c = SipHeader.canonicalize(it.name)
            c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.CALL_ID || c == SipHeader.CSEQ
        } + SipMessage.Header(SipHeader.TO,
            (req.toHeader() ?: "<sip:u@e>") + ";tag=server-tag")
        return SipResponse(statusCode = 200, reasonPhrase = "OK", headers = headers)
    }
}

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
import com.uvp.sim.testing.TestEngine

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
            password = "test-password"
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

    private fun ptzPositionSubscribeRequest(
        expires: Int = 10,
        callId: String = "ptz-sub-call@platform",
    ): SipRequest {
        val body = """<?xml version="1.0" encoding="GB2312"?>
<Query>
<CmdType>PTZPosition</CmdType>
<SN>18</SN>
<DeviceID>34020000001320000001</DeviceID>
</Query>""".encodeToByteArray()
        return SipRequest(
            method = SipMethod.SUBSCRIBE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-ptz"),
                SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=ptz-plat-tag"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
                SipMessage.Header(SipHeader.EVENT, "PTZPosition"),
                SipMessage.Header(SipHeader.EXPIRES, expires.toString()),
            ),
            body = body,
        )
    }

    private suspend fun registerEngine(transport: MockSipTransport, engine: SimulatorEngine) {
        engine.register()
        val regReq = transport.sent.filterIsInstance<SipRequest>().first { it.method == SipMethod.REGISTER }
        transport.deliver(fakeRegister200(regReq))
    }

    @Test
    fun subscribeResponds200AndSendsInitialNotify() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
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
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
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
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
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
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
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

    @Test
    fun ptzPositionSubscribeResponds200WithoutInitialOrPeriodicNotify() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(ptzPositionSubscribeRequest())
        runCurrent()
        advanceTimeBy(6_000)

        assertTrue(
            transport.sent.filterIsInstance<SipResponse>().any { it.statusCode == 200 },
            "PTZPosition SUBSCRIBE 必须收到 200 OK",
        )
        assertEquals(
            0,
            transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY },
            "订阅建立和时间流逝本身都不得伪造 PTZPosition NOTIFY",
        )
        assertTrue(engine.subscriptions.value["PtzPrecisePosition"]?.active == true)

        engine.shutdown()
    }

    @Test
    fun ptzActualChangeNotifiesAndOnlyMatching200CompletesDelivery() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(ptzPositionSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        engine.updatePoseFromRender(pan = 12.5f, tilt = -3.25f, zoom = 2f)
        runCurrent()

        val notify = transport.sent.filterIsInstance<SipRequest>().single { it.method == SipMethod.NOTIFY }
        val body = notify.body.decodeToString()
        assertEquals("PTZPosition", notify.firstHeader(SipHeader.EVENT))
        assertTrue(body.contains("<Response>"))
        assertTrue(body.contains("<CmdType>PTZPosition</CmdType>"))
        assertTrue(body.contains("<Pan>12.50</Pan>"))
        assertTrue(body.contains("<HorizontalFieldAngle>30.00</HorizontalFieldAngle>"))
        assertEquals(0, engine.subscriptions.value["PtzPrecisePosition"]?.notifyCount)
        assertEquals(SubscriptionLifecycle.Subscribing, engine.subscriptions.value["PtzPrecisePosition"]?.lifecycle)

        transport.deliver(notifyResponse(notify, 200))
        runCurrent()

        assertEquals(1, engine.subscriptions.value["PtzPrecisePosition"]?.notifyCount)
        assertEquals(SubscriptionLifecycle.Subscribed, engine.subscriptions.value["PtzPrecisePosition"]?.lifecycle)

        engine.shutdown()
    }

    @Test
    fun repeatedPtzPoseDoesNotSendDuplicateNotify() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(ptzPositionSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        engine.updatePoseFromRender(pan = 5f, tilt = 6f, zoom = 2f)
        runCurrent()
        engine.updatePoseFromRender(pan = 5f, tilt = 6f, zoom = 2f)
        runCurrent()

        assertEquals(
            1,
            transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY },
        )

        engine.shutdown()
    }

    @Test
    fun ptzPoseWithoutDialogOnlyUpdatesLocalState() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        engine.updatePoseFromRender(pan = 7f, tilt = -2f, zoom = 3f)
        runCurrent()

        assertEquals(7f, engine.deviceControlState.value.panAngle)
        assertEquals(-2f, engine.deviceControlState.value.tiltAngle)
        assertEquals(3f, engine.deviceControlState.value.zoomLevel)
        assertEquals(0, transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY })

        engine.shutdown()
    }

    @Test
    fun cancelledPtzDialogStopsPoseNotifications() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(ptzPositionSubscribeRequest(callId = "cancel-ptz@platform"))
        runCurrent()
        transport.deliver(ptzPositionSubscribeRequest(expires = 0, callId = "cancel-ptz@platform"))
        runCurrent()
        transport.sent.clear()

        engine.updatePoseFromRender(pan = 8f, tilt = 1f, zoom = 2f)
        runCurrent()

        assertEquals(0, transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY })
        assertEquals(
            SubscriptionLifecycle.Cancelled,
            engine.subscriptions.value["PtzPrecisePosition"]?.lifecycle,
        )

        engine.shutdown()
    }

    @Test
    fun platformPtzCommandFollowedByActualRenderPoseSendsNotify() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(ptzPositionSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        transport.deliver(deviceControlRequest())
        runCurrent()
        assertTrue(engine.deviceControlState.value.panSpeed < 0f)
        assertEquals(0, transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY })

        engine.updatePoseFromRender(pan = -4f, tilt = 0f, zoom = 1f)
        runCurrent()

        val notify = transport.sent.filterIsInstance<SipRequest>().single { it.method == SipMethod.NOTIFY }
        assertTrue(notify.body.decodeToString().contains("<Pan>-4.00</Pan>"))

        engine.shutdown()
    }

    @Test
    fun localPtzAdjustmentChangesSharedPoseAndSendsNotify() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(ptzPositionSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        engine.adjustLocalPtzPosition(panDelta = 5f, tiltDelta = -5f, zoomDelta = 0.5f)
        runCurrent()

        assertEquals(5f, engine.deviceControlState.value.panAngle)
        assertEquals(-5f, engine.deviceControlState.value.tiltAngle)
        assertEquals(1.5f, engine.deviceControlState.value.zoomLevel)
        val body = transport.sent.filterIsInstance<SipRequest>()
            .single { it.method == SipMethod.NOTIFY }
            .body.decodeToString()
        assertTrue(body.contains("<Pan>5.00</Pan>"))
        assertTrue(body.contains("<Tilt>-5.00</Tilt>"))
        assertTrue(body.contains("<Zoom>1.50</Zoom>"))

        engine.shutdown()
    }

    @Test
    fun platform4xxDoesNotCompleteAndNextRealChangeCanRetry() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(ptzPositionSubscribeRequest())
        runCurrent()
        transport.sent.clear()
        engine.updatePoseFromRender(1f, 2f, 2f)
        runCurrent()
        val firstNotify = transport.sent.filterIsInstance<SipRequest>().single { it.method == SipMethod.NOTIFY }

        transport.deliver(notifyResponse(firstNotify, 503))
        runCurrent()
        assertEquals(SubscriptionLifecycle.Exception, engine.subscriptions.value["PtzPrecisePosition"]?.lifecycle)
        assertEquals(0, engine.subscriptions.value["PtzPrecisePosition"]?.notifyCount)

        engine.updatePoseFromRender(2f, 2f, 2f)
        runCurrent()
        assertEquals(2, transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY })

        engine.shutdown()
    }

    @Test
    fun notifyTimeoutDoesNotRetrySameSnapshot() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(ptzPositionSubscribeRequest())
        runCurrent()
        transport.sent.clear()
        engine.updatePoseFromRender(3f, 2f, 2f)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(SubscriptionLifecycle.Exception, engine.subscriptions.value["PtzPrecisePosition"]?.lifecycle)
        assertEquals(1, transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY })
        engine.updatePoseFromRender(3f, 2f, 2f)
        runCurrent()
        assertEquals(1, transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY })

        engine.shutdown()
    }

    @Test
    fun inFlightPoseChangeSendsOnlyLatestAfterFirst200() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(ptzPositionSubscribeRequest())
        runCurrent()
        transport.sent.clear()
        engine.updatePoseFromRender(4f, 2f, 2f)
        runCurrent()
        val firstNotify = transport.sent.filterIsInstance<SipRequest>().single { it.method == SipMethod.NOTIFY }
        engine.updatePoseFromRender(6f, 2f, 2f)
        runCurrent()
        assertEquals(1, transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY })

        transport.deliver(notifyResponse(firstNotify, 200))
        runCurrent()
        val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.NOTIFY }
        assertEquals(2, notifies.size)
        assertTrue(notifies.last().body.decodeToString().contains("<Pan>6.00</Pan>"))

        transport.deliver(notifyResponse(notifies.last(), 200))
        runCurrent()
        assertEquals(2, engine.subscriptions.value["PtzPrecisePosition"]?.notifyCount)

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

    private fun notifyResponse(req: SipRequest, statusCode: Int): SipResponse = SipResponse(
        statusCode = statusCode,
        reasonPhrase = if (statusCode in 200..299) "OK" else "Failure",
        headers = req.headers.filter {
            val name = SipHeader.canonicalize(it.name)
            name == SipHeader.VIA || name == SipHeader.FROM || name == SipHeader.TO ||
                name == SipHeader.CALL_ID || name == SipHeader.CSEQ
        },
    )

    private fun deviceControlRequest(): SipRequest {
        val xml = "<?xml version=\"1.0\"?>" +
            "<Control><CmdType>DeviceControl</CmdType><SN>9</SN>" +
            "<DeviceID>34020000001320000001</DeviceID>" +
            "<PTZCmd>A50F0102320000E9</PTZCmd></Control>"
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:34020000001110000001@3402000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-control"),
                SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=server-tag"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, "ptz-control@platform"),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "Application/MANSCDP+xml"),
            ),
            body = xml.encodeToByteArray(),
        )
    }
}

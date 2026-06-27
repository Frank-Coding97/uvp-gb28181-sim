package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.Md5
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorEngineTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "127.0.0.1",
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
        keepaliveIntervalSeconds = 60
    )

    private fun fakeResponse(request: SipRequest, statusCode: Int, reasonPhrase: String,
                              extraHeaders: List<SipMessage.Header> = emptyList()): SipResponse {
        val baseHeaders = request.headers.filter {
            val k = SipHeader.canonicalize(it.name)
            k == SipHeader.VIA || k == SipHeader.FROM || k == SipHeader.CALL_ID || k == SipHeader.CSEQ
        } + SipMessage.Header(SipHeader.TO,
            (request.toHeader() ?: "<sip:u@e>") + ";tag=server-tag")
        return SipResponse(
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            headers = baseHeaders + extraHeaders
        )
    }

    @Test fun registerSucceedsAfter401Challenge() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()

            assertEquals(1, transport.sent.size, "first REGISTER should have been sent")
            val firstReq = transport.sent[0] as SipRequest
            assertEquals(SipMethod.REGISTER, firstReq.method)
            assertEquals(SipState.Registering, engine.state.value)

            val challenge = "Digest realm=\"3402000000\",nonce=\"testnonce\",algorithm=MD5"
            val resp401 = fakeResponse(firstReq, 401, "Unauthorized",
                listOf(SipMessage.Header(SipHeader.WWW_AUTHENTICATE, challenge)))
            transport.deliver(resp401)
            testScheduler.runCurrent()

            assertEquals(2, transport.sent.size, "second REGISTER (with auth) should have been sent")
            val authedReq = transport.sent[1] as SipRequest
            val authHeader = authedReq.firstHeader(SipHeader.AUTHORIZATION)
            assertNotNull(authHeader)
            val ha1 = Md5.hashHex("34020000001110000001:3402000000:wvp2025!!!")
            val ha2 = Md5.hashHex("REGISTER:${authedReq.requestUri}")
            val expected = Md5.hashHex("$ha1:testnonce:$ha2")
            assertTrue(authHeader.contains("response=\"$expected\""))
            assertEquals(SipState.Registering, engine.state.value)

            val resp200 = fakeResponse(authedReq, 200, "OK")
            transport.deliver(resp200)
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun ignoresDuplicate401StormAfterAuthedRegisterSent() = runTest {
        // Regression: UDP retransmits of one REGISTER make the platform answer several
        // fresh-nonce 401s. We must respond to the first challenge once and then ignore
        // the rest — otherwise each 401 spawns a new REGISTER, the platform rate-limits
        // ("register N times in 3 seconds"), and the device never registers.
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReq = transport.sent[0] as SipRequest

            transport.deliver(fakeResponse(firstReq, 401, "Unauthorized",
                listOf(SipMessage.Header(SipHeader.WWW_AUTHENTICATE,
                    "Digest realm=\"3402000000\",nonce=\"nonce-A\",algorithm=MD5"))))
            testScheduler.runCurrent()
            assertEquals(2, transport.sent.size, "first challenge → one authed REGISTER")

            // Storm: more 401s with different nonces arrive for the retransmitted REGISTER.
            repeat(5) { i ->
                val authed = transport.sent[1] as SipRequest
                transport.deliver(fakeResponse(authed, 401, "Unauthorized",
                    listOf(SipMessage.Header(SipHeader.WWW_AUTHENTICATE,
                        "Digest realm=\"3402000000\",nonce=\"nonce-storm-$i\",algorithm=MD5"))))
                testScheduler.runCurrent()
            }
            assertEquals(2, transport.sent.size,
                "duplicate 401s after auth must NOT spawn more REGISTERs")
            assertEquals(SipState.Registering, engine.state.value)

            // The single authed REGISTER still completes normally.
            val authedReq = transport.sent[1] as SipRequest
            transport.deliver(fakeResponse(authedReq, 200, "OK"))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun registerImmediate200Goes_Registered() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReq = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(firstReq, 200, "OK"))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun registerWith403Forbidden_goesFailed() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReq = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(firstReq, 403, "Forbidden"))
            testScheduler.runCurrent()
            assertEquals(SipState.Failed, engine.state.value)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun unregisterSendsRegisterWithExpires0() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReq = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(firstReq, 200, "OK"))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)

            val sentBefore = transport.sent.size
            engine.unregister()
            testScheduler.runCurrent()
            assertTrue(transport.sent.size > sentBefore, "Unregister should send a request")
            val unreg = transport.sent.last() as SipRequest
            assertEquals(SipMethod.REGISTER, unreg.method)
            assertEquals("0", unreg.firstHeader(SipHeader.EXPIRES))
            assertEquals(SipState.Disconnected, engine.state.value)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun registerTimeoutMovesToFailedAfterEightSeconds() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            assertEquals(SipState.Registering, engine.state.value)

            // 7s — still waiting (under threshold)
            testScheduler.advanceTimeBy(7_000)
            testScheduler.runCurrent()
            assertEquals(SipState.Registering, engine.state.value)

            // cross the 8s threshold — retry #1 scheduled (2s delay)
            testScheduler.advanceTimeBy(2_000)
            testScheduler.runCurrent()
            assertEquals(SipState.Registering, engine.state.value)

            // exhaust all retries: 2s delay + 8s timeout + 4s delay + 8s timeout + 8s delay + 8s timeout
            testScheduler.advanceTimeBy(2_000 + 8_000 + 4_000 + 8_000 + 8_000 + 8_000)
            testScheduler.runCurrent()
            assertEquals(SipState.Failed, engine.state.value)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun heartbeatStartsAfterRegistered() = runTest {
        val cfg = config().copy(keepaliveIntervalSeconds = 1)
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(cfg, transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReq = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(firstReq, 200, "OK"))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)

            val sentBefore = transport.sent.size
            // 移动 3.5s,期待 3 次 keepalive
            testScheduler.advanceTimeBy(3500)
            testScheduler.runCurrent()
            val keepalives = transport.sent.drop(sentBefore)
                .filter { it is SipRequest && it.method == SipMethod.MESSAGE }
            assertEquals(3, keepalives.size)
            val body = (keepalives.first() as SipRequest).body.decodeToString()
            assertTrue(body.contains("<CmdType>Keepalive</CmdType>"))
        } finally {
            engine.shutdown()
        }
    }

    @Test fun registerTimeoutCancelledOn200Ok() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()

            // Reply quickly, then advance past the 8s window — must remain Registered.
            val firstReq = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(firstReq, 200, "OK"))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)

            testScheduler.advanceTimeBy(20_000)
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun registerTimeoutResetOn401SoFullAuthFlowFits() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()

            // 7s passes, then platform answers 401. Timer should be re-armed,
            // so a 6s wait for the 200 OK still leaves us under threshold.
            testScheduler.advanceTimeBy(7_000)
            testScheduler.runCurrent()
            val firstReq = transport.sent[0] as SipRequest
            val challenge = "Digest realm=\"3402000000\",nonce=\"n\",algorithm=MD5"
            transport.deliver(fakeResponse(firstReq, 401, "Unauthorized",
                listOf(SipMessage.Header(SipHeader.WWW_AUTHENTICATE, challenge))))
            testScheduler.runCurrent()
            assertEquals(SipState.Registering, engine.state.value)

            testScheduler.advanceTimeBy(6_000)
            testScheduler.runCurrent()
            assertEquals(SipState.Registering, engine.state.value)

            val authedReq = transport.sent[1] as SipRequest
            transport.deliver(fakeResponse(authedReq, 200, "OK"))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun cancelRegisterReturnsToDisconnected() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            assertEquals(SipState.Registering, engine.state.value)

            engine.cancelRegister()
            testScheduler.runCurrent()
            assertEquals(SipState.Disconnected, engine.state.value)

            // Timer must be cancelled too — even past 10s we don't flip to Failed.
            testScheduler.advanceTimeBy(10_000)
            testScheduler.runCurrent()
            assertEquals(SipState.Disconnected, engine.state.value)
        } finally {
            engine.shutdown()
        }
    }

    /** ((B0+B1+B2+B3+B4+B5+B6) mod 256) hex. */
    private fun ptzHex(opCode: Int, pan: Int = 0, tilt: Int = 0, zoom: Int = 0): String {
        val b6 = (zoom and 0x0F) shl 4
        val sum = (0xA5 + 0x0F + 0x01 + opCode + pan + tilt + b6) and 0xFF
        return listOf(0xA5, 0x0F, 0x01, opCode, pan, tilt, b6, sum)
            .joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
    }

    private fun incomingMessage(callId: String, xmlBody: String): SipRequest = SipRequest(
        method = SipMethod.MESSAGE,
        requestUri = "sip:34020000001110000001@3402000000",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-test"),
            SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=server-tag"),
            SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
            SipMessage.Header(SipHeader.CONTENT_TYPE, "Application/MANSCDP+xml"),
            SipMessage.Header(SipHeader.CONTENT_LENGTH, xmlBody.length.toString()),
        ),
        body = xmlBody.encodeToByteArray()
    )

    private suspend fun TestScope.registerAnd200(
        transport: MockSipTransport,
        engine: SimulatorEngine
    ) {
        transport.connect()
        engine.register()
        testScheduler.runCurrent()
        val firstReq = transport.sent.first() as SipRequest
        val challenge = "Digest realm=\"3402000000\",nonce=\"n\",algorithm=MD5"
        transport.deliver(fakeResponse(firstReq, 401, "Unauthorized",
            listOf(SipMessage.Header(SipHeader.WWW_AUTHENTICATE, challenge))))
        testScheduler.runCurrent()
        val authedReq = transport.sent[1] as SipRequest
        transport.deliver(fakeResponse(authedReq, 200, "OK"))
        testScheduler.runCurrent()
    }

    @Test fun deviceControlPtzCmdUpdatesStateAndEmits() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this)
        val received = mutableListOf<SimEvent>()
        val sub = launch { engine.events.collect { received += it } }
        try {
            registerAnd200(transport, engine)
            val before = transport.sent.size
            val xml = "<?xml version=\"1.0\"?>" +
                "<Control><CmdType>DeviceControl</CmdType><SN>1</SN>" +
                "<DeviceID>34020000001320000001</DeviceID>" +
                "<PTZCmd>${ptzHex(0x02, pan = 50)}</PTZCmd></Control>"
            transport.deliver(incomingMessage("dc-call-1", xml))
            testScheduler.runCurrent()

            assertTrue(engine.deviceControlState.value.panSpeed < 0f,
                "panSpeed should be negative for LEFT, got ${engine.deviceControlState.value.panSpeed}")
            assertEquals("PTZCmd", engine.deviceControlState.value.lastCommand?.type)
            assertTrue(received.any { it is SimEvent.DeviceControlReceived && it.commandType == "PTZCmd" })
            // 200 OK 应已回送(register 期间也会有 sent 项,从 before 之后开始数)
            assertTrue(transport.sent.drop(before).any { it is SipResponse && it.statusCode == 200 })
        } finally {
            sub.cancel()
            engine.shutdown()
        }
    }

    @Test fun deviceControlIFameCmdRequestsKeyFrameAndEffect() = runTest {
        val transport = MockSipTransport(config())
        val engine = TestEngine.create(config(), transport, this)
        try {
            registerAnd200(transport, engine)
            val xml = "<Control><CmdType>DeviceControl</CmdType>" +
                "<DeviceID>34020000001320000001</DeviceID>" +
                "<IFameCmd>Send</IFameCmd></Control>"
            transport.deliver(incomingMessage("dc-call-2", xml))
            testScheduler.runCurrent()

            assertEquals(DeviceEffect.IFrameFlash, engine.deviceControlState.value.pendingEffect)
            assertEquals("IFameCmd", engine.deviceControlState.value.lastCommand?.type)
        } finally {
            engine.shutdown()
        }
    }
}

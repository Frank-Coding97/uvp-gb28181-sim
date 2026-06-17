package com.uvp.sim.domain

import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.media.H264Frame
import com.uvp.sim.media.NalType
import com.uvp.sim.network.RtpSender
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mock CameraCapture that emits a fixed list of pre-built frames.
 * The KMP expect class CameraCapture takes a CaptureConfig — the mock ignores
 * it and just hands back the canned frames when start() is collected.
 *
 * Note: we can't easily implement the expect/actual class in commonTest,
 * so this test relies on injecting a producer at the top level via an
 * interface; we work around by instantiating the platform-stub CameraCapture
 * and not relying on its frame output (we hand a separate frame producer to
 * the engine, see TestableEngine wrapper below in the test).
 *
 * Actually simpler: SimulatorEngine accepts a real CameraCapture only via its
 * .start() method returning a Flow<H264Frame>. We construct a CameraCapture
 * normally and ignore — instead we test the *INVITE handling path* without
 * media (engine returns early). Streaming bytes-level tests live in
 * RtpPackerTest / PsMuxerTest already.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingEngineTest {

    private fun cfg() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.10.222", port = 8160,
            serverId = "35020000002000000001", domain = "3502000000"
        ),
        device = DeviceConfig(
            deviceId = "35020000001310000001",
            videoChannelId = "35020000001320000001",
            alarmChannelId = "35020000001340000001",
            username = "35020000001310000001",
            password = "wvp_sip_password"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60
    )

    private fun inviteWithSdp(): SipRequest {
        val sdp = """
            v=0
            o=server 0 0 IN IP4 192.168.10.222
            s=Play
            c=IN IP4 192.168.10.222
            t=0 0
            m=video 30000 RTP/AVP 96
            a=recvonly
            a=rtpmap:96 PS/90000
            y=0100000001
        """.trimIndent().replace("\n", "\r\n")
        return SipRequest(
            method = SipMethod.INVITE,
            // GB §10: INVITE Request-URI 的 user 是被叫 channelId,不是 deviceId
            requestUri = "sip:35020000001320000001@3502000000",
            headers = listOf(
                SipMessage.Header("Via", "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bKabc"),
                SipMessage.Header("From", "<sip:35020000002000000001@3502000000>;tag=server"),
                SipMessage.Header("To", "<sip:35020000001320000001@3502000000>"),
                SipMessage.Header("Call-ID", "test-invite@server"),
                SipMessage.Header("CSeq", "1 INVITE"),
                SipMessage.Header("Contact", "<sip:34020000002000000001@192.168.10.222:8160>"),
                SipMessage.Header("Content-Type", "application/sdp")
            ),
            body = sdp.encodeToByteArray()
        )
    }

    @Test fun inviteWithoutMediaPlumbingStillTransitionsToInCall() = runTest {
        val transport = MockSipTransport()
        val engine = SimulatorEngine(cfg(), transport, this, localIpProvider = { "192.168.10.112" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReg = transport.sent[0] as SipRequest
            transport.deliver(fakeOk(firstReg))
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)

            transport.deliver(inviteWithSdp())
            testScheduler.runCurrent()

            assertEquals(SipState.InCall, engine.state.value)
            // No 200 OK was sent because rtpSenderFactory / cameraCapture are null
            assertEquals(1, transport.sent.size)  // only the original REGISTER
        } finally {
            engine.shutdown()
        }
    }

    @Test fun inviteWithMediaPlumbingSends200OkPlusSdpAnswer() = runTest {
        val transport = MockSipTransport()
        val capture = CameraCapture(CaptureConfig())
        val rtpFactory: (String, Int, com.uvp.sim.network.RtpMode) -> RtpSender = { host, port, mode ->
            // Use a real RtpSender against a closed port — bind succeeds, sends will
            // fail silently (we don't assert RTP delivery in commonTest).
            RtpSender(host, port, this, mode)
        }
        val engine = SimulatorEngine(
            cfg(), transport, this,
            localIpProvider = { "192.168.10.112" },
            cameraCapture = capture,
            rtpSenderFactory = rtpFactory
        )
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReg = transport.sent[0] as SipRequest
            transport.deliver(fakeOk(firstReg))
            testScheduler.runCurrent()

            transport.deliver(inviteWithSdp())
            testScheduler.runCurrent()

            // Engine should have sent the 200 OK
            val ok = transport.sent.lastOrNull { it is SipResponse } as? SipResponse
            assertNotNull(ok, "Engine should have sent 200 OK to INVITE")
            assertEquals(200, ok.statusCode)

            val body = ok.body.decodeToString()
            assertTrue(body.contains("v=0"), "200 OK body should contain SDP answer")
            assertTrue(body.contains("a=sendonly"))
            assertTrue(body.contains("y=0100000001"), "SDP answer must preserve offer y= SSRC")
            assertTrue(body.contains("c=IN IP4 192.168.10.112"))
        } finally {
            engine.shutdown()
        }
    }

    @Test fun byeStopsActiveStreamAndTransitionsToRegistered() = runTest {
        val transport = MockSipTransport()
        val capture = CameraCapture(CaptureConfig())
        val rtpFactory: (String, Int, com.uvp.sim.network.RtpMode) -> RtpSender = { host, port, mode ->
            RtpSender(host, port, this, mode)
        }
        val engine = SimulatorEngine(
            cfg(), transport, this,
            localIpProvider = { "192.168.10.112" },
            cameraCapture = capture,
            rtpSenderFactory = rtpFactory
        )
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReg = transport.sent[0] as SipRequest
            transport.deliver(fakeOk(firstReg))
            transport.deliver(inviteWithSdp())
            testScheduler.runCurrent()
            assertEquals(SipState.InCall, engine.state.value)

            // Now BYE
            val bye = SipRequest(
                method = SipMethod.BYE,
                requestUri = "sip:35020000001310000001@192.168.10.112:5060",
                headers = listOf(
                    SipMessage.Header("Via", "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bKbye"),
                    SipMessage.Header("From", "<sip:35020000002000000001@3502000000>;tag=server"),
                    SipMessage.Header("To", "<sip:35020000001310000001@3502000000>;tag=device"),
                    SipMessage.Header("Call-ID", "test-invite@server"),
                    SipMessage.Header("CSeq", "2 BYE")
                )
            )
            transport.deliver(bye)
            testScheduler.runCurrent()
            assertEquals(SipState.Registered, engine.state.value)

            // Engine should send a 200 OK to the BYE
            val byeOk = transport.sent.lastOrNull { it is SipResponse } as? SipResponse
            assertNotNull(byeOk)
            assertEquals(200, byeOk.statusCode)
        } finally {
            engine.shutdown()
        }
    }

    private fun fakeOk(req: SipRequest): SipResponse {
        val baseHeaders = req.headers.filter {
            val k = SipHeader.canonicalize(it.name)
            k == SipHeader.VIA || k == SipHeader.FROM ||
                k == SipHeader.CALL_ID || k == SipHeader.CSEQ
        } + SipMessage.Header(SipHeader.TO,
            (req.toHeader() ?: "<sip:u@e>") + ";tag=server-tag")
        return SipResponse(200, "OK", headers = baseHeaders)
    }
}

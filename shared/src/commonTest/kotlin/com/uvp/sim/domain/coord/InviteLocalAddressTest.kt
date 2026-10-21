package com.uvp.sim.domain.coord

import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.network.RtpSender
import com.uvp.sim.sip.*
import com.uvp.sim.testing.asEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InviteLocalAddressTest {
    @Test fun lan_signaling_with_vpn_media() = verifyRoutes(
        "192.168.124.3", "192.168.10.220", "192.168.124.4", "10.8.0.4",
    )

    @Test fun vpn_signaling_with_vpn_media() = verifyRoutes(
        "10.8.0.3", "192.168.10.220", "10.8.0.4", "10.8.0.4",
    )

    @Test fun lan_without_vpn() = verifyRoutes(
        "192.168.124.3", "192.168.124.5", "192.168.124.4", "192.168.124.4",
    )

    private fun verifyRoutes(platform: String, media: String, signalingIp: String, mediaIp: String) = runTest {
        val cfg = SimConfig(
            server = ServerConfig(platform, serverId = "34020000002000000002", domain = "3402000000"),
            device = DeviceConfig(deviceId = "37010301022000000007", videoChannelId = "34020000001320000020",
                alarmChannelId = "34020000001340000020", username = "37010301022000000007", password = "test"),
        )
        val channel = cfg.device.videoChannelId
        val tx = MockSipTransport(cfg)
        tx.connect()
        val destinations = mutableListOf<String>()
        val coord = InviteCoordinatorImpl(
            config = cfg, transport = tx, outbox = SipOutboxImpl(tx) {}, scope = backgroundScope,
            localIpProvider = { "10.8.0.4" }, localPortProvider = { 44992 },
            localIpFor = { host ->
                destinations += host
                when (host) { platform -> signalingIp; media -> mediaIp; else -> error("unexpected $host") }
            },
            cameraCapture = CameraCapture(CaptureConfig()),
            rtpSenderFactory = { host, port, mode, expected -> RtpSender(host, port, backgroundScope, mode, expected) },
            catalogTree = MutableStateFlow(listOf(CatalogNode(channel, CatalogNodeType.VideoChannel, "camera", cfg.device.deviceId))),
            mutableSipState = MutableStateFlow(SipState.Registered), autoStartMediaJobs = false,
        )
        val headers = listOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platform:5062;branch=z9hG4bK-test"),
            SipMessage.Header(SipHeader.FROM, "<sip:${cfg.server.serverId}@${cfg.server.domain}>;tag=platform"),
            SipMessage.Header(SipHeader.TO, "<sip:$channel@${cfg.server.domain}>"),
            SipMessage.Header(SipHeader.CALL_ID, "vpn-address-test"),
            SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
            SipMessage.Header(SipHeader.CONTACT, "<sip:${cfg.server.serverId}@$platform:5062>"),
        )
        val sdp = "v=0\r\no=server 0 0 IN IP4 $media\r\ns=Play\r\nc=IN IP4 $media\r\nt=0 0\r\nm=video 30000 RTP/AVP 96\r\na=recvonly\r\na=rtpmap:96 PS/90000\r\ny=0100000001\r\n"
        try {
            coord.onIncoming(SipRequest(SipMethod.INVITE, "sip:$channel@${cfg.server.domain}", headers = headers, body = sdp.encodeToByteArray()).asEnvelope(sourceIp = platform))
            runCurrent()
            val response = tx.sent.filterIsInstance<SipResponse>().single { it.statusCode == 200 }
            assertEquals("<sip:${cfg.device.deviceId}@$signalingIp:44992>", response.firstHeader(SipHeader.CONTACT))
            assertTrue(response.body.decodeToString().contains("c=IN IP4 $mediaIp\r\n"))
            assertEquals(setOf(platform, media), destinations.toSet())
            val dialogHeaders = headers.map {
                when (it.name) {
                    SipHeader.TO -> SipMessage.Header(SipHeader.TO, response.toHeader()!!)
                    SipHeader.CSEQ -> SipMessage.Header(SipHeader.CSEQ, "1 ACK")
                    else -> it
                }
            }
            coord.onIncoming(SipRequest(SipMethod.ACK, "sip:${cfg.device.deviceId}@$signalingIp:44992", headers = dialogHeaders).asEnvelope(sourceIp = platform))
            advanceTimeBy(33_000)
            runCurrent()
            assertNotNull(coord.activeStreamSnapshot.value, "ACK must cancel the watchdog")
            val byeHeaders = dialogHeaders.map { if (it.name == SipHeader.CSEQ) SipMessage.Header(it.name, "2 BYE") else it }
            coord.onIncoming(SipRequest(SipMethod.BYE, "sip:${cfg.device.deviceId}@$signalingIp:44992", headers = byeHeaders).asEnvelope(sourceIp = platform))
            assertNull(coord.activeStreamSnapshot.value)
            assertEquals(200, tx.sent.filterIsInstance<SipResponse>().last().statusCode)
        } finally {
            coord.shutdown()
            tx.close()
        }
    }
}

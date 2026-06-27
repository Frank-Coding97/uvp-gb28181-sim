package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.testing.asEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PR5 T5.3 GREEN:[BroadcastCoordinatorImpl] 直接路径覆盖。正向断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastCoordinatorTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.10.222", port = 8160,
            serverId = "35020000002000000001", domain = "3502000000",
        ),
        device = DeviceConfig(
            deviceId = "35020000001310000001",
            videoChannelId = "35020000001320000001",
            alarmChannelId = "35020000001340000001",
            username = "35020000001310000001",
            password = "p",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
    )

    /**
     * Fake RtpReceiver — 不开真 socket,bind 直接返回 50000。
     */
    private class FakeRxSource : com.uvp.sim.network.BroadcastRxSource {
        var bindCalled = 0
        override val localPort: Int = 50000
        override suspend fun bind(mode: com.uvp.sim.network.RtpMode): Int {
            bindCalled++
            return 50000
        }
        override suspend fun connect(remoteHost: String, remotePort: Int) {}
        override fun start(onPacket: (com.uvp.sim.network.RtpPacket) -> Unit): kotlinx.coroutines.Job =
            kotlinx.coroutines.Job()
        override suspend fun close() {}
    }

    private class FakeAudioSink : com.uvp.sim.media.AudioSink {
        override fun start() {}
        override fun write(pcm: ShortArray) {}
        override fun stop() {}
    }

    private fun newBc(
        scope: CoroutineScope,
        transport: MockSipTransport,
        rtpReceiverFactory: ((CoroutineScope) -> com.uvp.sim.network.BroadcastRxSource)? = { FakeRxSource() },
    ): BroadcastCoordinatorImpl =
        BroadcastCoordinatorImpl(
            config = config(),
            transport = transport,
            scope = scope,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
            rtpReceiverFactory = rtpReceiverFactory,
            audioSinkFactory = { _, _ -> FakeAudioSink() },
        )

    @Test
    fun t5_3_bc_a_fireBroadcastInvite_sends_outbound_INVITE() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val bc = newBc(this, transport)
        bc.fireBroadcastInvite(
            sourceId = "35020000002000000001",
            platformUri = "sip:35020000002000000001@3502000000",
            targetId = "35020000001310000001",
        )
        runCurrent()
        val invites = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.INVITE }
        assertEquals(1, invites.size, "应发出 outbound INVITE")
        val sdp = invites[0].body.decodeToString()
        assertTrue(sdp.contains("m=audio 50000"), "SDP offer 应含 bind 端口 50000")
        assertNotNull(bc.current.value, "current dialog 应建立")
        assertEquals("35020000001310000001", bc.current.value?.targetId)
    }

    @Test
    fun t5_3_bc_b_setSpeaker_works() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val bc = newBc(this, transport)
        assertEquals(true, bc.speakerOn.value)
        bc.setSpeaker(false); runCurrent()
        assertEquals(false, bc.speakerOn.value)
        bc.setSpeaker(true); runCurrent()
        assertEquals(true, bc.speakerOn.value)
    }

    @Test
    fun t5_3_bc_c_stop_with_no_active_is_noop() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val bc = newBc(this, transport)
        bc.stop()
        runCurrent()
        assertEquals(0, transport.sent.size, "无活跃 broadcast 时 stop 不应发消息")
        assertNull(bc.current.value)
    }

    @Test
    fun t5_3_bc_d_initial_state_no_dialog_and_debugSnapshot() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val bc = newBc(this, transport)
        assertNull(bc.current.value, "初始无活跃 broadcast")
        val snap = bc.debugSnapshot()
        assertEquals(0L, snap.rxPacketCount)
        assertEquals(0L, snap.decodeErrorCount)
        assertEquals(false, snap.rxActive)
    }

    @Test
    fun t5_3_bc_e_BYE_no_dialog_returns_Skip() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val bc = newBc(this, transport)
        val bye = SipRequest(
            method = SipMethod.BYE,
            requestUri = "sip:35020000001310000001@3502000000",
            headers = listOf(
                com.uvp.sim.sip.SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bK"),
                com.uvp.sim.sip.SipMessage.Header(SipHeader.FROM, "<sip:35020000002000000001@3502000000>;tag=plat"),
                com.uvp.sim.sip.SipMessage.Header(SipHeader.TO, "<sip:35020000001310000001@3502000000>;tag=local"),
                com.uvp.sim.sip.SipMessage.Header(SipHeader.CALL_ID, "no-such@plat"),
                com.uvp.sim.sip.SipMessage.Header(SipHeader.CSEQ, "1 BYE"),
            ),
            body = ByteArray(0),
        )
        val result = bc.onIncoming(bye.asEnvelope())
        runCurrent()
        assertEquals(RoutingResult.Skip, result, "BYE 不命中 dialog 必须 Skip")
    }
}

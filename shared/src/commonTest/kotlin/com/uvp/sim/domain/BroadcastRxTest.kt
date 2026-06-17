package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.media.G711
import com.uvp.sim.network.RtpPacket
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

/** T7 — RX 链路串联(handleRxPacket:PT 校验 → G711 decode → channel → 统计 / BroadcastStarted)。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastRxTest {

    private val deviceId = "34020000001320000001"
    private val platformId = "34020000002000000001"
    private val domain = "3402000000"

    private fun cfg() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(ip = "192.168.10.222", port = 5060, serverId = platformId, domain = domain),
        device = DeviceConfig(
            deviceId = deviceId, videoChannelId = deviceId,
            alarmChannelId = "34020000001340000001", username = deviceId, password = "p"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60
    )

    private fun broadcastMessage(): SipRequest {
        val body = """<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Broadcast</CmdType>
<SN>1</SN>
<SourceID>$platformId</SourceID>
<TargetID>$deviceId</TargetID>
</Notify>""".replace("\n", "\r\n")
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:$deviceId@$domain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:5060;branch=z9hG4bK-bc"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformId@$domain>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:$deviceId@$domain>"),
                SipMessage.Header(SipHeader.CALL_ID, "bc@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "application/MANSCDP+xml")
            ),
            body = body.encodeToByteArray()
        )
    }

    private fun ok200(invite: SipRequest, codec: Int): SipResponse = SipResponse(
        statusCode = 200, reasonPhrase = "OK",
        headers = invite.headers.filter {
            val c = SipHeader.canonicalize(it.name)
            c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.CALL_ID || c == SipHeader.CSEQ
        } + SipMessage.Header(SipHeader.TO, (invite.toHeader() ?: "<sip:p@d>") + ";tag=platbc") +
            SipMessage.Header(SipHeader.CONTACT, "<sip:$platformId@10.0.0.5:5060>"),
        body = """v=0
c=IN IP4 10.0.0.5
m=audio 30100 RTP/AVP $codec
a=rtpmap:$codec PCMA/8000
a=sendonly
""".trimIndent().replace("\n", "\r\n").encodeToByteArray()
    )

    private fun rtp(pt: Int, payload: ByteArray, seq: Int): RtpPacket = RtpPacket(
        version = 2, padding = false, extension = false, csrcCount = 0, marker = false,
        payloadType = pt, sequence = seq, timestamp = (seq * 160).toLong(), ssrc = 1L, payload = payload
    )

    private suspend fun bootRegistered(transport: MockSipTransport, engine: SimulatorEngine) {
        transport.connect()
        engine.register()
        val regReq = transport.sent.filterIsInstance<SipRequest>().first { it.method == SipMethod.REGISTER }
        val ok = SipResponse(
            statusCode = 200, reasonPhrase = "OK",
            headers = regReq.headers.filter {
                val c = SipHeader.canonicalize(it.name)
                c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.CALL_ID || c == SipHeader.CSEQ
            } + SipMessage.Header(SipHeader.TO, (regReq.toHeader() ?: "<sip:u@e>") + ";tag=server")
        )
        transport.deliver(ok)
    }

    private fun engineTo(transport: MockSipTransport, scope: kotlinx.coroutines.CoroutineScope) =
        SimulatorEngine(cfg(), transport, scope, localIp = "192.168.10.112", rtpReceiverFactory = { FakeBroadcastRxSource() })

    private suspend fun lastInvite(transport: MockSipTransport): SipRequest =
        transport.sent.filterIsInstance<SipRequest>().last { it.method == SipMethod.INVITE }

    @Test
    fun firstPacketEmitsBroadcastStartedOnce() = runTest {
        val transport = MockSipTransport()
        val engine = engineTo(transport, this)
        val started = mutableListOf<SimEvent.BroadcastStarted>()
        val job = launch { engine.events.collect { if (it is SimEvent.BroadcastStarted) started += it } }
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()

        val pcma = G711.encodeAlaw(shortArrayOf(0, 100, -200))
        engine.handleRxPacket(rtp(pt = 8, payload = pcma, seq = 1))
        engine.handleRxPacket(rtp(pt = 8, payload = pcma, seq = 2))
        runCurrent()

        assertEquals(1, started.size, "BroadcastStarted 只在第一包 emit")
        assertEquals(2L, engine.rxPacketCountForTest())
        job.cancel()
        engine.shutdown()
    }

    @Test
    fun nonG711PayloadCountsDecodeError() = runTest {
        val transport = MockSipTransport()
        val engine = engineTo(transport, this)
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()

        engine.handleRxPacket(rtp(pt = 96, payload = ByteArray(160), seq = 1))
        runCurrent()

        assertEquals(1L, engine.decodeErrorCountForTest())
        assertEquals(0L, engine.rxPacketCountForTest())
        engine.shutdown()
    }

    @Test
    fun stopBroadcastCancelsRxJob() = runTest {
        val transport = MockSipTransport()
        val engine = engineTo(transport, this)
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()
        assertTrue(engine.isRxActive(), "Talking 后 RX 协程应活跃")

        engine.stopBroadcast(BroadcastEndReason.Local)
        runCurrent()
        assertFalse(engine.isRxActive(), "停止后 RX 协程应取消")
        engine.shutdown()
    }
}

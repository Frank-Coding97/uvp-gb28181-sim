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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T9 — audioChannel consumer + AudioPlayback lifecycle(start / stop 随 dialog 生灭)。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastPlaybackTest {

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

    private fun lastInvite(transport: MockSipTransport): SipRequest =
        transport.sent.filterIsInstance<SipRequest>().last { it.method == SipMethod.INVITE }

    @Test
    fun playbackStartsOnTalkingAndStopsOnLocalStop() = runTest {
        val transport = MockSipTransport()
        val fakeSink = FakeAudioSink()
        val engine = SimulatorEngine(
            cfg(), transport, this, localIp = "192.168.10.112",
            rtpReceiverFactory = { FakeBroadcastRxSource() },
            audioSinkFactory = { _, _ -> fakeSink }
        )
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()

        assertEquals(1, fakeSink.startCount, "Talking 后扬声器应 start 一次")
        assertEquals(0, fakeSink.stopCount)

        engine.stopBroadcast(BroadcastEndReason.Local)
        runCurrent()
        assertEquals(1, fakeSink.stopCount, "停止后扬声器应 stop")
        engine.shutdown()
    }

    @Test
    fun rxPacketReachesPlaybackWrite() = runTest {
        val transport = MockSipTransport()
        val fakeSink = FakeAudioSink()
        val engine = SimulatorEngine(
            cfg(), transport, this, localIp = "192.168.10.112",
            rtpReceiverFactory = { FakeBroadcastRxSource() },
            audioSinkFactory = { _, _ -> fakeSink }
        )
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()

        val pcma = G711.encodeAlaw(ShortArray(160) { 0 })
        engine.handleRxPacket(
            RtpPacket(2, false, false, 0, false, 8, 1, 160L, 1L, pcma)
        )
        runCurrent()

        assertTrue(fakeSink.writeCount >= 1, "RX 包应经 channel 消费到 AudioPlayback.write")
        engine.stopBroadcast(BroadcastEndReason.Local)
        runCurrent()
        engine.shutdown()
    }

    @Test
    fun mutedSpeakerSkipsWrite() = runTest {
        val transport = MockSipTransport()
        val fakeSink = FakeAudioSink()
        val engine = SimulatorEngine(
            cfg(), transport, this, localIp = "192.168.10.112",
            rtpReceiverFactory = { FakeBroadcastRxSource() },
            audioSinkFactory = { _, _ -> fakeSink }
        )
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()

        engine.setBroadcastSpeaker(false)  // 静音
        runCurrent()
        val pcma = G711.encodeAlaw(ShortArray(160) { 0 })
        engine.handleRxPacket(RtpPacket(2, false, false, 0, false, 8, 1, 160L, 1L, pcma))
        engine.handleRxPacket(RtpPacket(2, false, false, 0, false, 8, 2, 320L, 1L, pcma))
        runCurrent()

        assertEquals(0, fakeSink.writeCount, "静音时不应写 AudioTrack")
        // 但仍在收包(统计照常累加)
        assertEquals(2L, engine.rxPacketCountForTest())
        engine.stopBroadcast(BroadcastEndReason.Local)
        runCurrent()
        engine.shutdown()
    }
}

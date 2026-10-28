package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.RtpPacket
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.testing.TestEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BroadcastAudioThreadTest {
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

    private fun ok200(invite: SipRequest): SipResponse = SipResponse(
        statusCode = 200,
        reasonPhrase = "OK",
        headers = invite.headers.filter {
            val name = SipHeader.canonicalize(it.name)
            name == SipHeader.VIA || name == SipHeader.FROM || name == SipHeader.CALL_ID || name == SipHeader.CSEQ
        } + SipMessage.Header(SipHeader.TO, (invite.toHeader() ?: "<sip:p@d>") + ";tag=platbc") +
            SipMessage.Header(SipHeader.CONTACT, "<sip:$platformId@10.0.0.5:5060>"),
        body = """v=0
c=IN IP4 10.0.0.5
m=audio 30100 RTP/AVP 8
a=rtpmap:8 PCMA/8000
a=sendonly
""".trimIndent().replace("\n", "\r\n").encodeToByteArray()
    )

    private suspend fun bootRegistered(transport: MockSipTransport, engine: SimulatorEngine) {
        transport.connect()
        engine.register()
        val register = transport.sent.filterIsInstance<SipRequest>().first { it.method == SipMethod.REGISTER }
        transport.deliver(
            SipResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = register.headers.filter {
                    val name = SipHeader.canonicalize(it.name)
                    name == SipHeader.VIA || name == SipHeader.FROM || name == SipHeader.CALL_ID || name == SipHeader.CSEQ
                } + SipMessage.Header(SipHeader.TO, (register.toHeader() ?: "<sip:u@e>") + ";tag=server")
            )
        )
    }

    @Test
    fun audioSinkLifecycleRunsOffCallingThread() = runTest {
        val callerThread = Thread.currentThread()
        val transport = MockSipTransport()
        val sink = ThreadRecordingAudioSink()
        val engine = TestEngine.create(
            cfg(), transport, this,
            localIpProvider = { "192.168.10.112" },
            rtpReceiverFactory = { FakeBroadcastRxSource() },
            audioSinkFactory = { _, _ -> sink }
        )

        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        val invite = transport.sent.filterIsInstance<SipRequest>().last { it.method == SipMethod.INVITE }
        transport.deliver(ok200(invite))
        runCurrent()

        engine.handleRxPacket(
            RtpPacket(2, false, false, 0, false, 8, 1, 160L, 1L, byteArrayOf(0xD5.toByte()))
        )
        withTimeout(2_000) { sink.firstWrite.await() }
        engine.stopBroadcast(BroadcastEndReason.Local)
        engine.shutdown()

        assertTrue(
            sink.startThread !== callerThread && sink.writeThread !== callerThread && sink.stopThread !== callerThread,
            "AudioSink start/write/stop must run off the SIP caller thread; " +
                "start=${sink.startThread?.name}, write=${sink.writeThread?.name}, stop=${sink.stopThread?.name}, " +
                "caller=${callerThread.name}"
        )
    }

    private class ThreadRecordingAudioSink : AudioSink {
        var startThread: Thread? = null
        var writeThread: Thread? = null
        var stopThread: Thread? = null
        val firstWrite = CompletableDeferred<Unit>()

        override fun start(): Boolean {
            startThread = Thread.currentThread()
            return true
        }

        override fun write(pcm: ShortArray) {
            writeThread = Thread.currentThread()
            firstWrite.complete(Unit)
        }

        override fun stop() {
            stopThread = Thread.currentThread()
        }
    }
}

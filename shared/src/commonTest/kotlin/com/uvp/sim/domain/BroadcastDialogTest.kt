package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * T3 — 反向 INVITE + broadcast dialog 状态机
 * (Inviting → Talking / InviteFailed / CodecRejected / Local BYE / Remote BYE / 并发 busy)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastDialogTest {

    private val deviceId = "34020000001320000001"
    private val platformId = "34020000002000000001"
    private val domain = "3402000000"

    private fun cfg() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(ip = "192.168.10.222", port = 5060, serverId = platformId, domain = domain),
        device = DeviceConfig(
            deviceId = deviceId,
            videoChannelId = deviceId,
            alarmChannelId = "34020000001340000001",
            username = deviceId,
            password = "p"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60
    )

    private fun broadcastMessage(targetId: String = deviceId, callId: String = "bc@plat", sn: String = "1"): SipRequest {
        val body = """<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Broadcast</CmdType>
<SN>$sn</SN>
<SourceID>$platformId</SourceID>
<TargetID>$targetId</TargetID>
</Notify>""".replace("\n", "\r\n")
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:$deviceId@$domain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:5060;branch=z9hG4bK-bc$sn"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformId@$domain>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:$deviceId@$domain>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "application/MANSCDP+xml")
            ),
            body = body.encodeToByteArray()
        )
    }

    private fun answerSdp(codec: Int): String = """v=0
o=platform 0 0 IN IP4 10.0.0.5
s=Broadcast
c=IN IP4 10.0.0.5
t=0 0
m=audio 30100 RTP/AVP $codec
a=rtpmap:$codec PCMA/8000
a=sendonly
""".trimIndent().replace("\n", "\r\n")

    private fun copyDialogHeaders(invite: SipRequest) = invite.headers.filter {
        val c = SipHeader.canonicalize(it.name)
        c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.CALL_ID || c == SipHeader.CSEQ
    }

    private fun ok200(invite: SipRequest, codec: Int): SipResponse = SipResponse(
        statusCode = 200, reasonPhrase = "OK",
        headers = copyDialogHeaders(invite) +
            SipMessage.Header(SipHeader.TO, (invite.toHeader() ?: "<sip:p@d>") + ";tag=platbc") +
            SipMessage.Header(SipHeader.CONTACT, "<sip:$platformId@10.0.0.5:5060>"),
        body = answerSdp(codec).encodeToByteArray()
    )

    private fun failure(invite: SipRequest, code: Int, reason: String): SipResponse = SipResponse(
        statusCode = code, reasonPhrase = reason,
        headers = copyDialogHeaders(invite) +
            SipMessage.Header(SipHeader.TO, (invite.toHeader() ?: "<sip:p@d>") + ";tag=platbc")
    )

    private fun platformBye(callId: String): SipRequest = SipRequest(
        method = SipMethod.BYE,
        requestUri = "sip:$deviceId@192.168.10.112:5060",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:5060;branch=z9hG4bK-bye"),
            SipMessage.Header(SipHeader.FROM, "<sip:$platformId@$domain>;tag=platbc"),
            SipMessage.Header(SipHeader.TO, "<sip:$deviceId@$domain>;tag=devbc"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "2 BYE")
        )
    )

    private fun lastInvite(transport: MockSipTransport): SipRequest =
        transport.sent.filterIsInstance<SipRequest>().last { it.method == SipMethod.INVITE }

    private fun inviteCount(transport: MockSipTransport): Int =
        transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.INVITE }

    private fun byeCount(transport: MockSipTransport): Int =
        transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.BYE }

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

    /**
     * baseline red · task 12:iOS 真实 AudioSink (AVAudioEngine) 在 simulator 无音频硬件时启动失败,
     * 走 R1 #7 引入的"sink.start 失败 → BYE + teardown"路径,把 _current.value 清成 null,
     * 断言 Talking 全跪。所有 broadcast 相关测试统一注入 FakeAudioSink 让 sink.start 返回 true。
     */
    private fun sinkFactory(): (Int, Int) -> com.uvp.sim.media.AudioSink = { _, _ -> FakeAudioSink() }

    @Test
    fun broadcastMessageTriggersInviteAndInvitingState() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() }, audioSinkFactory = sinkFactory())
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(broadcastMessage())
        runCurrent()

        assertEquals(1, inviteCount(transport), "应发一个 outbound INVITE")
        val invite = lastInvite(transport)
        assertEquals("sip:$platformId@$domain", invite.requestUri)
        assertTrue(invite.body.decodeToString().contains("m=audio"), "INVITE 应携带 m=audio offer")
        assertEquals(BroadcastDialogState.Inviting, engine.currentBroadcast.value?.state)
        engine.shutdown()
    }

    @Test
    fun ok200WithG711LeadsToTalkingAndAck() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() }, audioSinkFactory = sinkFactory())
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()

        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()

        val ack = transport.sent.filterIsInstance<SipRequest>().lastOrNull { it.method == SipMethod.ACK }
        assertNotNull(ack, "200 OK 后应发 ACK")
        assertEquals(BroadcastDialogState.Talking, engine.currentBroadcast.value?.state)
        assertEquals(AudioRxCodec.PCMA, engine.currentBroadcast.value?.codec)
        assertEquals("10.0.0.5", engine.currentBroadcast.value?.remoteAudioHost)
        engine.shutdown()
    }

    @Test
    fun invite488ClearsDialogWithInviteFailed() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() }, audioSinkFactory = sinkFactory())
        val ended = mutableListOf<SimEvent.BroadcastEnded>()
        val job = launch { engine.events.collect { if (it is SimEvent.BroadcastEnded) ended += it } }
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()

        transport.deliver(failure(lastInvite(transport), 488, "Not Acceptable Here"))
        runCurrent()

        assertNull(engine.currentBroadcast.value, "488 后应清 dialog")
        assertTrue(ended.any { it.reason == BroadcastEndReason.InviteFailed })
        job.cancel()
        engine.shutdown()
    }

    @Test
    fun codec96RejectedWithByeAndCodecRejected() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() }, audioSinkFactory = sinkFactory())
        val ended = mutableListOf<SimEvent.BroadcastEnded>()
        val job = launch { engine.events.collect { if (it is SimEvent.BroadcastEnded) ended += it } }
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        val byeBefore = byeCount(transport)

        transport.deliver(ok200(lastInvite(transport), codec = 96))
        runCurrent()

        assertEquals(byeBefore + 1, byeCount(transport), "编码不可接受应发 BYE")
        assertNull(engine.currentBroadcast.value)
        assertTrue(ended.any { it.reason == BroadcastEndReason.CodecRejected })
        job.cancel()
        engine.shutdown()
    }

    @Test
    fun userStopBroadcastSendsByeAndClears() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() }, audioSinkFactory = sinkFactory())
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()
        val byeBefore = byeCount(transport)

        engine.stopBroadcast(BroadcastEndReason.Local)
        runCurrent()

        assertEquals(byeBefore + 1, byeCount(transport))
        assertNull(engine.currentBroadcast.value)
        engine.shutdown()
    }

    @Test
    fun platformByeRepliesOkAndClears() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() }, audioSinkFactory = sinkFactory())
        val ended = mutableListOf<SimEvent.BroadcastEnded>()
        val job = launch { engine.events.collect { if (it is SimEvent.BroadcastEnded) ended += it } }
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()
        val cid = engine.currentBroadcast.value!!.callId
        transport.sent.clear()

        transport.deliver(platformBye(cid))
        runCurrent()

        val ok = transport.sent.filterIsInstance<SipResponse>().lastOrNull { it.statusCode == 200 }
        assertNotNull(ok, "平台 BYE 应回 200 OK")
        assertNull(engine.currentBroadcast.value)
        assertTrue(ended.any { it.reason == BroadcastEndReason.Remote })
        job.cancel()
        engine.shutdown()
    }

    @Test
    fun secondBroadcastWhileTalkingRepliesBusyWithoutInvite() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() }, audioSinkFactory = sinkFactory())
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage(callId = "bc-1@plat", sn = "1"))
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()
        transport.sent.clear()

        transport.deliver(broadcastMessage(callId = "bc-2@plat", sn = "2"))
        runCurrent()

        assertEquals(0, inviteCount(transport), "Talking 时第二路不应再发 INVITE")
        val busy = transport.sent.filterIsInstance<SipRequest>()
            .map { it.body.decodeToString() }
            .filter { it.contains("<CmdType>Broadcast</CmdType>") }
        assertTrue(busy.any { it.contains("<Result>ERROR</Result>") && it.contains("<Reason>busy</Reason>") }, "应回 ERROR busy")
        engine.shutdown()
    }

    // ---- R3 cross-review #1 (CRITICAL/security): mid-dialog BYE 必须 4 元组校验 ----

    /** 攻击者抓到 Call-ID 后,带错的 from-tag 发 BYE → 应返 481,不 teardown。 */
    @Test
    fun spoofedBye_wrongFromTag_returns481_andKeepsDialog() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() }, audioSinkFactory = sinkFactory())
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()
        val cid = engine.currentBroadcast.value!!.callId
        transport.sent.clear()

        val spoof = SipRequest(
            method = SipMethod.BYE,
            requestUri = "sip:$deviceId@192.168.10.112:5060",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:5060;branch=z9hG4bK-spoof"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformId@$domain>;tag=ATTACKER"),
                SipMessage.Header(SipHeader.TO, "<sip:$deviceId@$domain>;tag=devbc"),
                SipMessage.Header(SipHeader.CALL_ID, cid),
                SipMessage.Header(SipHeader.CSEQ, "9 BYE"),
            ),
        )
        transport.deliver(spoof)
        runCurrent()

        val resp481 = transport.sent.filterIsInstance<SipResponse>().lastOrNull { it.statusCode == 481 }
        assertNotNull(resp481, "伪造 from-tag 的 BYE 应回 481")
        val ok200 = transport.sent.filterIsInstance<SipResponse>().any { it.statusCode == 200 }
        assertEquals(false, ok200, "不能给伪造 BYE 发 200")
        assertNotNull(engine.currentBroadcast.value, "dialog 仍应活跃")
        assertEquals(BroadcastDialogState.Talking, engine.currentBroadcast.value?.state)
        engine.shutdown()
    }

    /** 攻击者从不同 IP 发 BYE(callId + tag 都对) → 应返 481,不 teardown。 */
    @Test
    fun spoofedBye_wrongSourceIp_returns481_andKeepsDialog() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() }, audioSinkFactory = sinkFactory())
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(broadcastMessage())
        runCurrent()
        transport.deliver(ok200(lastInvite(transport), codec = 8))
        runCurrent()
        val cid = engine.currentBroadcast.value!!.callId
        transport.sent.clear()

        transport.deliver(platformBye(cid), sourceIp = "10.66.66.66")
        runCurrent()

        val resp481 = transport.sent.filterIsInstance<SipResponse>().lastOrNull { it.statusCode == 481 }
        assertNotNull(resp481, "异源 IP BYE 应回 481")
        assertNotNull(engine.currentBroadcast.value, "异源 BYE 不应踢掉对话")
        assertEquals(BroadcastDialogState.Talking, engine.currentBroadcast.value?.state)
        engine.shutdown()
    }
}

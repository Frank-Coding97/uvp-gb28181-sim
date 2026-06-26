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
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * T1 — 平台 Broadcast MESSAGE 路由 + Broadcast Response(OK / ERROR)。
 * T1 阶段不发反向 INVITE(那是 T3)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastRoutingTest {

    private val deviceId = "34020000001310000001"
    private val videoChannelId = "34020000001320000001"
    private val platformId = "34020000002000000001"

    private fun cfg() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.10.222", port = 5060,
            serverId = "34020000002000000001", domain = "3402000000"
        ),
        device = DeviceConfig(
            deviceId = deviceId,
            videoChannelId = videoChannelId,
            alarmChannelId = "34020000001340000001",
            username = deviceId,
            password = "p"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60
    )

    private fun broadcastMessage(targetId: String, sn: String = "1"): SipRequest {
        val body = """<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Broadcast</CmdType>
<SN>$sn</SN>
<SourceID>$platformId</SourceID>
<TargetID>$targetId</TargetID>
</Notify>""".replace("\n", "\r\n")
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:$deviceId@3402000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:5060;branch=z9hG4bK-bc"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformId@3402000000>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:$deviceId@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, "bc-$sn@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "application/MANSCDP+xml")
            ),
            body = body.encodeToByteArray()
        )
    }

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

    private fun broadcastResponseMessages(transport: MockSipTransport): List<String> =
        transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.MESSAGE }
            .map { it.body.decodeToString() }
            .filter { it.contains("<CmdType>Broadcast</CmdType>") }

    @Test
    fun targetIdMatchRepliesOk() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() })
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(broadcastMessage(targetId = deviceId))
        runCurrent()

        // 应先发 200 OK,再发一条 Broadcast Response MESSAGE(Result=OK)
        val ok200 = transport.sent.filterIsInstance<SipResponse>().filter { it.statusCode == 200 }
        assertTrue(ok200.isNotEmpty(), "应先回 200 OK")
        val resp = broadcastResponseMessages(transport)
        assertEquals(1, resp.size, "应发一条 Broadcast Response")
        assertTrue(resp[0].contains("<Result>OK</Result>"), "TargetID 匹配应回 OK")
        engine.shutdown()
    }

    @Test
    fun targetIdMismatchRepliesError() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() })
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(broadcastMessage(targetId = "99999999999999999999"))
        runCurrent()

        val resp = broadcastResponseMessages(transport)
        assertEquals(1, resp.size)
        assertTrue(resp[0].contains("<Result>ERROR</Result>"), "TargetID 不匹配应回 ERROR")
        assertTrue(resp[0].contains("<Reason>target mismatch</Reason>"))
        engine.shutdown()
    }

    @Test
    fun channelIdTargetRepliesOk() = runTest {
        // 平台对**视频通道**发起对讲(TargetID = videoChannelId,≠ deviceId)→ 也应接受
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() })
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(broadcastMessage(targetId = videoChannelId))
        runCurrent()

        val resp = broadcastResponseMessages(transport)
        assertEquals(1, resp.size)
        assertTrue(resp[0].contains("<Result>OK</Result>"), "通道 ID 作为 TargetID 也应回 OK")
        // 且应主动发反向 INVITE
        assertTrue(
            transport.sent.filterIsInstance<SipRequest>().any { it.method == SipMethod.INVITE },
            "通道对讲也应发反向 INVITE"
        )
        engine.shutdown()
    }

    @Test
    fun matchEmitsBroadcastReceived() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(), transport, this, localIpProvider = { "192.168.10.112" }, rtpReceiverFactory = { FakeBroadcastRxSource() })
        val received = mutableListOf<SimEvent.BroadcastReceived>()
        val job = launch { engine.events.collect { if (it is SimEvent.BroadcastReceived) received += it } }
        bootRegistered(transport, engine)
        runCurrent()

        transport.deliver(broadcastMessage(targetId = deviceId))
        runCurrent()

        assertNotNull(received.firstOrNull())
        assertEquals(platformId, received.first().sourceId)
        assertEquals(deviceId, received.first().targetId)
        job.cancel()
        engine.shutdown()
    }
}

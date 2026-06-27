package com.uvp.sim.domain

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * P1-2: handleInvite 按 channelId 类型路由 — 不支持的类型立即 488。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteRoutingTest {

    private fun cfg(catalogTree: List<CatalogNode> = emptyList()) = SimConfig(
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
            password = "p"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        catalogTree = catalogTree
    )

    private fun inviteFor(channelId: String, callId: String = "inv-$channelId@plat"): SipRequest {
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
            requestUri = "sip:$channelId@3502000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.10.222:8160;branch=z9hG4bK-inv"),
                SipMessage.Header(SipHeader.FROM, "<sip:35020000002000000001@3502000000>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:$channelId@3502000000>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
                SipMessage.Header(SipHeader.CONTACT, "<sip:35020000002000000001@192.168.10.222:8160>"),
                SipMessage.Header("Content-Type", "application/sdp")
            ),
            body = sdp.encodeToByteArray()
        )
    }

    private fun fullTree() = listOf(
        CatalogNode("35020000001310000001", CatalogNodeType.Device, "Dev", "35020000001310000001"),
        CatalogNode("35020000001320000001", CatalogNodeType.VideoChannel, "Cam", "35020000001310000001"),
        CatalogNode("35020000001340000001", CatalogNodeType.AlarmChannel, "Alm", "35020000001310000001"),
        CatalogNode("35020000001370000001", CatalogNodeType.BusinessGroup, "G1", "35020000001310000001"),
        CatalogNode("35020000001380000001", CatalogNodeType.VirtualOrg, "区划", "35020000001310000001")
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
            } + SipMessage.Header(SipHeader.TO,
                (regReq.toHeader() ?: "<sip:u@e>") + ";tag=server")
        )
        transport.deliver(ok)
    }

    @Test
    fun inviteVideoChannelIsAccepted() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(fullTree()), transport, this, localIpProvider = { "192.168.10.112" })
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(inviteFor("35020000001320000001"))
        runCurrent()

        // 视频通道 INVITE 应被接受 — 不应有 488 响应
        // (实际 200 OK 的发送依赖 rtpSenderFactory 不为 null,这里 null 走"early return"路径不发响应,
        // 但绝对不应该出现 488)
        val rejections = transport.sent.filterIsInstance<SipResponse>().filter { it.statusCode == 488 }
        assertEquals(0, rejections.size, "视频通道 INVITE 不应被 488 拒绝")
        engine.shutdown()
    }

    @Test
    fun inviteAlarmChannelIsRejectedWith488() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(fullTree()), transport, this, localIpProvider = { "192.168.10.112" })
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(inviteFor("35020000001340000001"))
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertEquals(1, responses.size)
        assertEquals(488, responses[0].statusCode)
        assertTrue(responses[0].reasonPhrase.contains("alarm"), "理由应说明报警通道不流")
        engine.shutdown()
    }

    @Test
    fun inviteDeviceRootIsRejectedWith488() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(fullTree()), transport, this, localIpProvider = { "192.168.10.112" })
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(inviteFor("35020000001310000001"))
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertEquals(1, responses.size)
        assertEquals(488, responses[0].statusCode)
        assertTrue(responses[0].reasonPhrase.contains("device root"))
        engine.shutdown()
    }

    @Test
    fun inviteBusinessGroupIsRejectedWith488() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(fullTree()), transport, this, localIpProvider = { "192.168.10.112" })
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(inviteFor("35020000001370000001"))
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertEquals(1, responses.size)
        assertEquals(488, responses[0].statusCode)
        assertTrue(responses[0].reasonPhrase.contains("business group"))
        engine.shutdown()
    }

    @Test
    fun inviteVirtualOrgIsRejectedWith488() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(fullTree()), transport, this, localIpProvider = { "192.168.10.112" })
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(inviteFor("35020000001380000001"))
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertEquals(1, responses.size)
        assertEquals(488, responses[0].statusCode)
        assertTrue(responses[0].reasonPhrase.contains("virtual org"))
        engine.shutdown()
    }

    @Test
    fun inviteUnknownChannelIdFallsThroughWithoutRejection() = runTest {
        // 兼容性:不在 catalogTree 里的 channelId(老版本 SimConfig.device.videoChannelId 等)
        // 不应被 488 拒绝 — 走原 INVITE 路径(rtpSenderFactory null 场景下早 return)
        val transport = MockSipTransport()
        val engine = TestEngine.create(cfg(emptyList()), transport, this, localIpProvider = { "192.168.10.112" })
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(inviteFor("99999999991329999999"))  // 不存在
        runCurrent()

        val rejections = transport.sent.filterIsInstance<SipResponse>().filter { it.statusCode == 488 }
        assertEquals(0, rejections.size, "兼容旧 SimConfig — 找不到的 channelId 不应被 488")
        engine.shutdown()
    }

    // ---- T4: 双真实通道并发拒绝(B 方案 486) ----

    /** 注入真实 jvm RtpSender + jvm CameraCapture stub,使首路 INVITE 能建出 activeStream。 */
    private fun mediaEngine(transport: MockSipTransport, scope: kotlinx.coroutines.CoroutineScope) =
        TestEngine.create(
            cfg(fullTree()), transport, scope, localIpProvider = { "192.168.10.112" },
            cameraCapture = com.uvp.sim.camera.CameraCapture(com.uvp.sim.camera.CaptureConfig()),
            rtpSenderFactory = { host, port, mode, expectedClientHost ->
                com.uvp.sim.network.RtpSender(host, port, scope, mode, expectedClientHost)
            }
        )

    @Test
    fun concurrentSecondInviteRejectedWith486() = runTest {
        val transport = MockSipTransport()
        val engine = mediaEngine(transport, this)
        bootRegistered(transport, engine)
        runCurrent()
        transport.sent.clear()

        // 第一路:后置视频通道 → 建出 activeStream(cam stub 空 flow,但 activeStream 已置)
        transport.deliver(inviteFor("35020000001320000001", callId = "call-1@plat"))
        runCurrent()
        assertEquals(
            0,
            transport.sent.filterIsInstance<SipResponse>().count { it.statusCode == 486 },
            "第一路不应 486"
        )

        // 第二路:任意视频通道 → 期望 486 Busy Here
        transport.deliver(inviteFor("35020000001320000001", callId = "call-2@plat"))
        runCurrent()
        val busy = transport.sent.filterIsInstance<SipResponse>().filter { it.statusCode == 486 }
        assertEquals(1, busy.size, "并发第二路应 486")
        assertTrue(busy[0].reasonPhrase.contains("Busy", ignoreCase = true))
        engine.shutdown()
    }

    @Test
    fun concurrentBusyDoesNotOverrideTypeRejection488() = runTest {
        // 已有活跃流时,对报警通道 INVITE 仍应 488(类型路由在并发检查之前)
        val transport = MockSipTransport()
        val engine = mediaEngine(transport, this)
        bootRegistered(transport, engine)
        runCurrent()
        transport.deliver(inviteFor("35020000001320000001", callId = "call-1@plat"))
        runCurrent()
        transport.sent.clear()

        transport.deliver(inviteFor("35020000001340000001", callId = "call-alarm@plat"))  // 报警通道
        runCurrent()
        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertEquals(1, responses.size)
        assertEquals(488, responses[0].statusCode, "报警通道优先 488,不被 486 覆盖")
        engine.shutdown()
    }
}

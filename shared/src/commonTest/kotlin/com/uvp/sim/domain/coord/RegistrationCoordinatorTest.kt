package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.network.NetworkState
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.testing.asEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PR2 T2.1 RED:[RegistrationCoordinatorImpl] 6 用例。
 *
 * 当前 Impl 是 stub(全 TODO),这些测试**应该全部失败 / 抛 NotImplementedError**。
 * T2.2 GREEN 阶段填实现后,本测试转为全绿。
 *
 * 测试策略:直接调 [RegistrationCoordinator.onIncoming] / [register] / [unregister],
 * 不依赖 Engine 总分发(plan 第 2.1 节策略 + T2.1 RED 风格 A)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistrationCoordinatorTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.1.100", port = 5060,
            serverId = "34020000002000000001", domain = "3402000000",
        ),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "wvp2025!!!",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915),
    )

    private fun newCoord(scope: kotlinx.coroutines.CoroutineScope, transport: MockSipTransport): RegistrationCoordinatorImpl =
        RegistrationCoordinatorImpl(
            config = config(),
            transport = transport,
            scope = scope,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            localIpProvider = { "192.168.1.50" },
            localPortProvider = { 5060 },
        )

    private fun fake401(callId: String, fromTag: String): SipResponse = SipResponse(
        statusCode = 401,
        reasonPhrase = "Unauthorized",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/UDP 192.168.1.50:5060;rport;branch=z9hG4bK-reg-1"),
            SipMessage.Header(SipHeader.FROM,
                "<sip:34020000001110000001@3402000000>;tag=$fromTag"),
            SipMessage.Header(SipHeader.TO,
                "<sip:34020000001110000001@3402000000>;tag=plat-401"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 REGISTER"),
            SipMessage.Header("WWW-Authenticate",
                "Digest realm=\"3402000000\",nonce=\"abc123\",algorithm=MD5"),
        ),
    )

    private fun fake200OkRegister(callId: String, fromTag: String, dateHeader: String? = null): SipResponse = SipResponse(
        statusCode = 200,
        reasonPhrase = "OK",
        headers = buildList {
            add(SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/UDP 192.168.1.50:5060;rport;branch=z9hG4bK-reg-2"))
            add(SipMessage.Header(SipHeader.FROM,
                "<sip:34020000001110000001@3402000000>;tag=$fromTag"))
            add(SipMessage.Header(SipHeader.TO,
                "<sip:34020000001110000001@3402000000>;tag=plat-ok"))
            add(SipMessage.Header(SipHeader.CALL_ID, callId))
            add(SipMessage.Header(SipHeader.CSEQ, "2 REGISTER"))
            add(SipMessage.Header(SipHeader.EXPIRES, "3600"))
            dateHeader?.let { add(SipMessage.Header(SipHeader.DATE, it)) }
        },
    )

    private fun fakeOptions(callId: String = "opt-1@plat"): SipRequest = SipRequest(
        method = SipMethod.OPTIONS,
        requestUri = "sip:34020000001110000001@192.168.1.50:5060",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/UDP 192.168.1.100:5060;rport;branch=z9hG4bK-opt-$callId"),
            SipMessage.Header(SipHeader.FROM,
                "<sip:34020000002000000001@3402000000>;tag=plat-tag"),
            SipMessage.Header(SipHeader.TO,
                "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 OPTIONS"),
            SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
        ),
    )

    // -------- 6 RED 用例 --------

    @Test
    fun t2_1_a_register_sends_REGISTER_with_correct_uri() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val coord = newCoord(this, transport)

        coord.register()
        runCurrent()

        val req = transport.sent.filterIsInstance<SipRequest>().firstOrNull()
        assertNotNull(req, "register() 应该发出一条 REGISTER")
        assertEquals(SipMethod.REGISTER, req.method)
        assertEquals(
            "sip:34020000002000000001@3402000000",
            req.requestUri,
            "request URI 应指向 serverId@domain",
        )
        assertEquals(RegistrationState.Registering, coord.state.value)
    }

    @Test
    fun t2_1_b_register_401_then_authorized_REGISTER_with_digest() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val coord = newCoord(this, transport)

        coord.register()
        runCurrent()
        val firstReq = transport.sent.filterIsInstance<SipRequest>().first()
        val callId = firstReq.firstHeader(SipHeader.CALL_ID)!!
        val fromTag = firstReq.firstHeader(SipHeader.FROM)!!.substringAfter("tag=")

        transport.sent.clear()
        coord.onIncoming(fake401(callId, fromTag).asEnvelope())
        runCurrent()

        val secondReq = transport.sent.filterIsInstance<SipRequest>().firstOrNull()
        assertNotNull(secondReq, "401 之后必须重发 REGISTER 带 Authorization")
        assertEquals(SipMethod.REGISTER, secondReq.method)
        val auth = secondReq.firstHeader(SipHeader.AUTHORIZATION)
        assertNotNull(auth, "重发的 REGISTER 必须含 Authorization 头")
        assertTrue(auth.startsWith("Digest "), "Authorization 必须用 Digest")
        assertTrue(auth.contains("realm=\"3402000000\""), "Digest 必须复用 challenge realm")
    }

    @Test
    fun t2_1_c_register_200_OK_transitions_to_Registered_and_parses_Date() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val coord = newCoord(this, transport)

        coord.register()
        runCurrent()
        val firstReq = transport.sent.filterIsInstance<SipRequest>().first()
        val callId = firstReq.firstHeader(SipHeader.CALL_ID)!!
        val fromTag = firstReq.firstHeader(SipHeader.FROM)!!.substringAfter("tag=")

        coord.onIncoming(
            fake200OkRegister(callId, fromTag, dateHeader = "Mon, 22 Jun 2026 08:00:00 GMT").asEnvelope(),
        )
        runCurrent()

        assertEquals(RegistrationState.Registered, coord.state.value)
        val off = coord.clockOffset.value
        assertTrue(off != com.uvp.sim.domain.ClockOffset.Empty,
            "200 OK Date 头应被解析到 clockOffset")
    }

    @Test
    fun t2_1_d_unregister_after_registered_sends_REGISTER_with_expires_0() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val coord = newCoord(this, transport)

        coord.register()
        runCurrent()
        val firstReq = transport.sent.filterIsInstance<SipRequest>().first()
        val callId = firstReq.firstHeader(SipHeader.CALL_ID)!!
        val fromTag = firstReq.firstHeader(SipHeader.FROM)!!.substringAfter("tag=")
        coord.onIncoming(fake200OkRegister(callId, fromTag).asEnvelope())
        runCurrent()
        transport.sent.clear()

        coord.unregister()
        runCurrent()

        val unreg = transport.sent.filterIsInstance<SipRequest>().firstOrNull()
        assertNotNull(unreg, "unregister() 必须发一条 REGISTER")
        assertEquals(SipMethod.REGISTER, unreg.method)
        assertEquals(
            "0",
            unreg.firstHeader(SipHeader.EXPIRES),
            "unregister REGISTER 必须含 Expires: 0",
        )
    }

    @Test
    fun t2_1_e_network_change_bound_when_registered_triggers_reregister() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val coord = newCoord(this, transport)

        coord.register()
        runCurrent()
        val firstReq = transport.sent.filterIsInstance<SipRequest>().first()
        val callId = firstReq.firstHeader(SipHeader.CALL_ID)!!
        val fromTag = firstReq.firstHeader(SipHeader.FROM)!!.substringAfter("tag=")
        coord.onIncoming(fake200OkRegister(callId, fromTag).asEnvelope())
        runCurrent()
        val countBefore = transport.sent.size

        coord.onNetworkChange(
            NetworkState.Bound(
                preference = NetworkPreference.AUTO,
                interfaceName = "wlan0",
                localIp = "192.168.1.51",
            ),
        )
        runCurrent()

        assertTrue(
            transport.sent.size > countBefore,
            "网络切到 Bound 应触发重注册(unregister + register)",
        )
    }

    @Test
    fun t2_1_f_OPTIONS_request_replies_200_with_Allow_header() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val coord = newCoord(this, transport)

        coord.onIncoming(fakeOptions().asEnvelope())
        runCurrent()

        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp, "OPTIONS 必须有响应出栈")
        assertEquals(200, resp.statusCode)
        val allow = resp.firstHeader("Allow")
        assertNotNull(allow, "OPTIONS 200 必须含 Allow 头")
    }

    // -------- T2.3a (2026-06-23) — SN 池 provider 路径 --------
    // 验证当注入 cseqProvider/cseqIncrementer 时,Coord 走外部 SN 池而非自管 counter。
    // 这是 T2.3b Engine 委派的前置:让 Reg 跟 Engine 共享同一份全局 cseq。
    // 详见研究文档 wiki/projects/uvp-gb28181-sim/research/2026-06-23-cseq-sn-pool-coupling.md。

    @Test
    fun t2_3a_register_uses_injected_cseqIncrementer_for_REGISTER_CSeq() = runTest {
        val transport = MockSipTransport()
        transport.connect()

        // 模拟 Engine 全局 SN 池 — 起始 cseq=42,Coord register() 时该走 cseqInc 推到 43
        var sharedCseq = 42
        val coord = RegistrationCoordinatorImpl(
            config = config(),
            transport = transport,
            scope = this,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            localIpProvider = { "192.168.1.50" },
            localPortProvider = { 5060 },
            cseqProvider = { sharedCseq },
            cseqIncrementer = { sharedCseq += 1; sharedCseq },
        )

        coord.register()
        runCurrent()

        val req = transport.sent.filterIsInstance<SipRequest>().firstOrNull()
        assertNotNull(req, "register() 应发出 REGISTER")
        // register() 内 cseqResetTo(1) 在注入模式下不重置(ownsCseqPool=false)。
        // 后续没有 cseqInc → REGISTER 包的 CSeq 头读到的是 sharedCseq 当前值(42)。
        val cseqHeader = req.firstHeader(SipHeader.CSEQ)
        assertNotNull(cseqHeader, "REGISTER 必须含 CSeq 头")
        assertEquals(
            "42 REGISTER",
            cseqHeader,
            "注入 cseqProvider 时,REGISTER 包的 CSeq 必须读外部 SN 池当前值(不重置)",
        )
        assertEquals(42, sharedCseq, "register() 不应在注入模式下推进 SN 池")
    }

    @Test
    fun t2_3a_unregister_uses_injected_cseqIncrementer() = runTest {
        val transport = MockSipTransport()
        transport.connect()

        var sharedCseq = 100
        val coord = RegistrationCoordinatorImpl(
            config = config(),
            transport = transport,
            scope = this,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            localIpProvider = { "192.168.1.50" },
            localPortProvider = { 5060 },
            cseqProvider = { sharedCseq },
            cseqIncrementer = { sharedCseq += 1; sharedCseq },
        )

        coord.register()
        runCurrent()
        val firstReq = transport.sent.filterIsInstance<SipRequest>().first()
        val callId = firstReq.firstHeader(SipHeader.CALL_ID)!!
        val fromTag = firstReq.firstHeader(SipHeader.FROM)!!.substringAfter("tag=")
        coord.onIncoming(fake200OkRegister(callId, fromTag).asEnvelope())
        runCurrent()
        transport.sent.clear()

        val cseqBeforeUnreg = sharedCseq

        coord.unregister()
        runCurrent()

        val unreg = transport.sent.filterIsInstance<SipRequest>().firstOrNull()
        assertNotNull(unreg, "unregister() 必须发 REGISTER")
        // unregister() 内 cseqInc() 推一次,REGISTER 包的 CSeq 必须 = cseqBeforeUnreg + 1
        val unregCseqHeader = unreg.firstHeader(SipHeader.CSEQ)
        assertNotNull(unregCseqHeader, "unregister REGISTER 必须含 CSeq 头")
        assertEquals(
            "${cseqBeforeUnreg + 1} REGISTER",
            unregCseqHeader,
            "unregister 必须通过 cseqIncrementer 推进外部 SN 池",
        )
        assertEquals(
            cseqBeforeUnreg + 1,
            sharedCseq,
            "外部 SN 池应被推进一次",
        )
    }
}

package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import com.uvp.sim.testing.TestEngine

/**
 * A2 — SimulatorEngine.handleOptions 路由(M5 平台兼容性补漏 batch1).
 * 平台 OPTIONS 探活 → 200 OK + Allow 头 + 不污染其他事务.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptionsHandlingTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.1.100", port = 5060,
            serverId = "34020000002000000001", domain = "3402000000"
        ),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "wvp2025!!!"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915)
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
            SipMessage.Header(SipHeader.MAX_FORWARDS, "70")
        )
    )

    private suspend fun connectEngine(): Pair<MockSipTransport, SimulatorEngine> {
        val transport = MockSipTransport(config())
        transport.connect()
        return transport to transport
            .let { tr -> tr to TestEngine.create(config(), tr, kotlinx.coroutines.GlobalScope) }
            .second
    }

    @Test
    fun a2_t1_probeOptionsReturns200() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        // 触发 inbound job 启动(register 后才订阅 transport.incoming)
        engine.register()
        runCurrent()
        transport.sent.clear()

        transport.deliver(fakeOptions())
        runCurrent()

        val resp = transport.sent.filterIsInstance<SipResponse>().firstOrNull()
        assertNotNull(resp, "OPTIONS 必须有响应出栈")
        assertEquals(200, resp.statusCode)

        engine.shutdown()
    }

    @Test
    fun a2_t2_responseHasAllowHeaderWithAllExpectedMethods() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        engine.register()
        runCurrent()
        transport.sent.clear()

        transport.deliver(fakeOptions("opt-allow"))
        runCurrent()

        val resp = transport.sent.filterIsInstance<SipResponse>().first { it.statusCode == 200 }
        val allow = resp.firstHeader("Allow")
        assertNotNull(allow, "OPTIONS 200 必须含 Allow 头")
        // 真实支持的方法集
        listOf("INVITE", "ACK", "BYE", "MESSAGE", "SUBSCRIBE",
               "NOTIFY", "CANCEL", "INFO", "OPTIONS").forEach { m ->
            assertTrue(allow.contains(m), "Allow 缺少 $m: $allow")
        }

        engine.shutdown()
    }

    @Test
    fun a2_t3_allowDoesNotIncludeRegister() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        engine.register()
        runCurrent()
        transport.sent.clear()

        transport.deliver(fakeOptions("opt-no-reg"))
        runCurrent()

        val resp = transport.sent.filterIsInstance<SipResponse>().first { it.statusCode == 200 }
        val allow = resp.firstHeader("Allow") ?: ""
        // REGISTER 设备只发不收,不应在 Allow 列举
        assertFalse(
            Regex("""\bREGISTER\b""").containsMatchIn(allow),
            "Allow 不应含 REGISTER(设备只发不收): $allow"
        )

        engine.shutdown()
    }

    @Test
    fun a2_t4_optionsDoesNotInterfereWithOtherTransactions() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        engine.register()
        runCurrent()
        transport.sent.clear()

        // 先收 OPTIONS,再收 MESSAGE — MESSAGE 路径(handleMessage)应正常 200 OK
        transport.deliver(fakeOptions("opt-first"))
        runCurrent()

        val msgReq = SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA,
                    "SIP/2.0/UDP 192.168.1.100:5060;rport;branch=z9hG4bK-msg-1"),
                SipMessage.Header(SipHeader.FROM,
                    "<sip:34020000002000000001@3402000000>;tag=plat-msg"),
                SipMessage.Header(SipHeader.TO,
                    "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, "msg-after-opt"),
                SipMessage.Header(SipHeader.CSEQ, "2 MESSAGE"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "application/MANSCDP+xml"),
                SipMessage.Header(SipHeader.MAX_FORWARDS, "70")
            ),
            body = "<Notify><CmdType>Keepalive</CmdType></Notify>".encodeToByteArray()
        )
        transport.deliver(msgReq)
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>().filter { it.statusCode == 200 }
        // 至少一条 OPTIONS 响应 + 一条 MESSAGE 响应
        assertTrue(responses.size >= 2, "OPTIONS 后 MESSAGE 仍应正常 200 OK,实收 ${responses.size} 条 200")

        engine.shutdown()
    }
}

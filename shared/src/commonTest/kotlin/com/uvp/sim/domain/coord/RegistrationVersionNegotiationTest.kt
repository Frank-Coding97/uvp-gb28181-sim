package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.testing.asEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 附录 I:设备要从**注册响应**里得知平台支持的协议版本。
 *
 * 修复前的行为是「只把版本报出去,从不看平台回了什么」—— 于是无论对面平台是 2016 还是 2022,
 * 设备都按自己选的版本出站,标准里"双方在注册过程中得知对方支持的协议版本"这一半完全缺失。
 *
 * 这里同时钉住两件事:
 *  1. 成功(200 OK)**与失败(401 挑战)**响应里的版本都要采纳 —— 附录 I 原文写明
 *     「无论是成功或失败」;
 *  2. 注销后清空 —— "在注册过程中得知"是每次注册会话的事,不该跨会话继承。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistrationVersionNegotiationTest {

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
            password = "test-password",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915),
    )

    private fun newCoord(scope: kotlinx.coroutines.CoroutineScope, transport: MockSipTransport) =
        RegistrationCoordinatorImpl(
            config = config(),
            transport = transport,
            scope = scope,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            localIpProvider = { "192.168.1.50" },
            localPortProvider = { 5060 },
        )

    private fun registerResponse(
        callId: String,
        fromTag: String,
        statusCode: Int,
        reasonPhrase: String,
        version: String?,
    ): SipResponse = SipResponse(
        statusCode = statusCode,
        reasonPhrase = reasonPhrase,
        headers = buildList {
            add(SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/UDP 192.168.1.50:5060;rport;branch=z9hG4bK-reg"))
            add(SipMessage.Header(SipHeader.FROM,
                "<sip:34020000001110000001@3402000000>;tag=$fromTag"))
            add(SipMessage.Header(SipHeader.TO,
                "<sip:34020000001110000001@3402000000>;tag=plat"))
            add(SipMessage.Header(SipHeader.CALL_ID, callId))
            add(SipMessage.Header(SipHeader.CSEQ, "2 REGISTER"))
            if (statusCode == 401) {
                add(SipMessage.Header("WWW-Authenticate",
                    "Digest realm=\"3402000000\",nonce=\"abc123\",algorithm=MD5"))
            }
            version?.let { add(SipMessage.Header(SipHeader.X_GB_VER, it)) }
        },
    )

    /** register() 之后取回本轮的 Call-ID / From tag,用于构造匹配的响应。 */
    private fun pendingIdentity(transport: MockSipTransport): Pair<String, String> {
        val req = transport.sent.filterIsInstance<SipRequest>().first()
        return req.firstHeader(SipHeader.CALL_ID)!! to req.firstHeader(SipHeader.FROM)!!.substringAfter("tag=")
    }

    @Test
    fun `200 OK 里的平台版本被采纳 2022 乘 2016 协商出 2016`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        coord.onIncoming(registerResponse(callId, fromTag, 200, "OK", version = "2.0").asEnvelope())
        runCurrent()

        assertEquals(GbVersion.V2016, coord.platformVersion.value,
            "平台声明 2.0 时设备应得知对面只支持 2016")
        coord.shutdown()
    }

    @Test
    fun `401 挑战里的版本同样被采纳`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        coord.onIncoming(registerResponse(callId, fromTag, 401, "Unauthorized", version = "2.0").asEnvelope())
        runCurrent()

        assertEquals(GbVersion.V2016, coord.platformVersion.value,
            "附录 I 要求失败响应也带版本,所以 401 就该完成协商")
        coord.shutdown()
    }

    @Test
    fun `平台声明 3_0 时有效版本仍是本机的 2022`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        coord.onIncoming(registerResponse(callId, fromTag, 200, "OK", version = "3.0").asEnvelope())
        runCurrent()

        assertEquals(GbVersion.V2022, coord.platformVersion.value)
        coord.shutdown()
    }

    @Test
    fun `平台不声明版本时协商结果为 null 而不是降级`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        coord.onIncoming(registerResponse(callId, fromTag, 200, "OK", version = null).asEnvelope())
        runCurrent()

        assertNull(coord.platformVersion.value, "未声明 ≠ 只支持 2016,不该当成降级信号")
        coord.shutdown()
    }

    @Test
    fun `平台声明表 I 1 之外的版本时协商结果为 null`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        coord.onIncoming(registerResponse(callId, fromTag, 200, "OK", version = "9.9").asEnvelope())
        runCurrent()

        assertNull(coord.platformVersion.value)
        coord.shutdown()
    }

    @Test
    fun `注销后清空协商结论`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        coord.onIncoming(registerResponse(callId, fromTag, 200, "OK", version = "2.0").asEnvelope())
        runCurrent()
        assertEquals(GbVersion.V2016, coord.platformVersion.value)
        transport.sent.clear()

        val job = launch { coord.unregister() }
        runCurrent()
        assertEquals(
            SipMethod.REGISTER,
            transport.sent.filterIsInstance<SipRequest>().firstOrNull()?.method,
            "unregister() 应先发出 Expires=0 的 REGISTER",
        )
        coord.onIncoming(registerResponse(callId, fromTag, 200, "OK", version = "2.0").asEnvelope())
        runCurrent()
        job.join()

        assertNull(coord.platformVersion.value, "协商结论只对一次注册会话成立")
        coord.shutdown()
    }
}

package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.NatSituation
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipOutboxImpl
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.testing.asEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * §9.1.1 f) 的适用性判据:设备要从**注册响应**里发现自己是否处于地址转换之后。
 *
 * 为什么这条判定必须由设备自己做:平台不会主动告诉设备"你在我这里显示成什么地址",
 * 它只会在响应 Via 的 `received` / `rport`(RFC 3581)里回填。不知道这一点,现场就只能
 * 看到"注册成功、但点播/云台/查询全部超时"——这个现象在 NAT、防火墙丢包、平台内部
 * 异常下长得完全一样。
 *
 * ⛔ 同时钉住一条**容易被"优化"掉的语义**:平台不回填时结果是 UNKNOWN(未知),不是 DIRECT
 * (没在 NAT 后)。把"不知道"当成"没问题",正是这类故障能在现场拖半个月的原因。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistrationRportObservationTest {

    private val localIp = "192.168.1.50"
    private val localPort = 5060

    private fun config(transport: TransportType = TransportType.UDP) = SimConfig(
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
        transport = transport,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915),
    )

    private fun newCoord(
        scope: CoroutineScope,
        transport: MockSipTransport,
        sipTransport: TransportType = TransportType.UDP,
    ) = RegistrationCoordinatorImpl(
        config = config(sipTransport),
        transport = transport,
        scope = scope,
        outbox = SipOutboxImpl(transport) {},
        localIpProvider = { localIp },
        localPortProvider = { localPort },
    )

    /**
     * @param via 响应里的 Via 值。默认为我们**发出去的那个裸 ;rport**(平台原样回显 =
     *            平台不支持 RFC 3581),这也是最常见的情形。
     */
    private fun registerResponse(
        callId: String,
        fromTag: String,
        via: String = "SIP/2.0/UDP $localIp:$localPort;rport;branch=z9hG4bK-reg",
        version: String? = "3.0",
        statusCode: Int = 200,
    ): SipResponse = SipResponse(
        statusCode = statusCode,
        reasonPhrase = if (statusCode == 401) "Unauthorized" else "OK",
        headers = buildList {
            add(SipMessage.Header(SipHeader.VIA, via))
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

    private fun pendingIdentity(transport: MockSipTransport): Pair<String, String> {
        val req = transport.sent.filterIsInstance<SipRequest>().first()
        return req.firstHeader(SipHeader.CALL_ID)!! to req.firstHeader(SipHeader.FROM)!!.substringAfter("tag=")
    }

    @Test
    fun `平台回填的公网端点与本机不一致时判定为处于地址转换之后`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        coord.onIncoming(
            registerResponse(
                callId, fromTag,
                via = "SIP/2.0/UDP $localIp:$localPort;rport=51234;received=203.0.113.5;branch=z9hG4bK-reg",
            ).asEnvelope(),
        )
        runCurrent()

        val observation = assertNotNull(coord.rportObservation.value, "平台回填了就必须给出观察值")
        assertEquals(NatSituation.NAT, observation.situation)
        assertEquals("203.0.113.5", observation.observedIp)
        assertEquals(51234, observation.observedPort)
        // 本机端点原样带出,UI 要做"平台看到 X / 本机 Y"的对照展示
        assertEquals(localIp, observation.localIp)
        assertEquals(localPort, observation.localPort)
        coord.shutdown()
    }

    @Test
    fun `平台回填与本机一致时判定为无地址转换`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        coord.onIncoming(
            registerResponse(
                callId, fromTag,
                via = "SIP/2.0/UDP $localIp:$localPort;rport=$localPort;received=$localIp;branch=z9hG4bK-reg",
            ).asEnvelope(),
        )
        runCurrent()

        assertEquals(NatSituation.DIRECT, coord.rportObservation.value?.situation)
        coord.shutdown()
    }

    @Test
    fun `平台原样回显裸 rport 时判定为未知而不是无地址转换`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        // 默认 via 即裸 ;rport —— 平台不支持 RFC 3581 时的真实回显形态
        coord.onIncoming(registerResponse(callId, fromTag).asEnvelope())
        runCurrent()

        assertEquals(
            NatSituation.UNKNOWN,
            coord.rportObservation.value?.situation,
            "「不知道」不能被当成「没问题」",
        )
        coord.shutdown()
    }

    @Test
    fun `注销后清空观察值`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)
        coord.register()
        runCurrent()
        val (callId, fromTag) = pendingIdentity(transport)

        coord.onIncoming(
            registerResponse(
                callId, fromTag,
                via = "SIP/2.0/UDP $localIp:$localPort;rport=51234;received=203.0.113.5;branch=z9hG4bK-reg",
            ).asEnvelope(),
        )
        runCurrent()
        assertEquals(NatSituation.NAT, coord.rportObservation.value?.situation)
        transport.sent.clear()

        val job = launch { coord.unregister() }
        runCurrent()
        coord.onIncoming(registerResponse(callId, fromTag).asEnvelope())
        runCurrent()
        job.join()

        assertNull(coord.rportObservation.value, "观察值只对一次注册会话成立")
        coord.shutdown()
    }

    /**
     * **去重逻辑的回归点。**
     *
     * 协商结论与观察值都会被注销清空,而重新注册时平台报的版本头/回填**通常与上次完全相同**。
     * 若把"结论没变就别重复处理"的判据放在值更新**之前**,流会永远停在 null —— 设置页
     * 一直显示"注册后协商",而实际早就协商完了。这个 bug 在真实使用里极难发现(要"注销 →
     * 再注册 → 看设置页"),所以在这里用一个用例钉死。
     */
    @Test
    fun `注销后重新注册时即使平台回值不变也要恢复流`() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val coord = newCoord(this, transport)

        val observedVia =
            "SIP/2.0/UDP $localIp:$localPort;rport=51234;received=203.0.113.5;branch=z9hG4bK-reg"

        // 第一次注册
        coord.register()
        runCurrent()
        val (firstCallId, firstTag) = pendingIdentity(transport)
        coord.onIncoming(registerResponse(firstCallId, firstTag, via = observedVia, version = "2.0").asEnvelope())
        runCurrent()
        assertEquals(GbVersion.V2016, coord.platformVersion.value)
        assertEquals(NatSituation.NAT, coord.rportObservation.value?.situation)

        // 注销 → 两条流都被清空
        transport.sent.clear()
        val job = launch { coord.unregister() }
        runCurrent()
        coord.onIncoming(registerResponse(firstCallId, firstTag, via = observedVia, version = "2.0").asEnvelope())
        runCurrent()
        job.join()
        assertNull(coord.platformVersion.value)
        assertNull(coord.rportObservation.value)

        // 第二次注册:平台回的版本头与回填**与上次一模一样**
        transport.sent.clear()
        coord.register()
        runCurrent()
        val (secondCallId, secondTag) = pendingIdentity(transport)
        coord.onIncoming(registerResponse(secondCallId, secondTag, via = observedVia, version = "2.0").asEnvelope())
        runCurrent()

        assertEquals(
            GbVersion.V2016,
            coord.platformVersion.value,
            "值更新必须无条件执行,去重只能用来压日志",
        )
        assertEquals(
            NatSituation.NAT,
            coord.rportObservation.value?.situation,
            "观察值同样必须恢复,否则设置页会一直停在'平台未回填'",
        )
        coord.shutdown()
    }
}

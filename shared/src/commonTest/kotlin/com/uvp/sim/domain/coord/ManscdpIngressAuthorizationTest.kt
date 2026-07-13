package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmHistoryStore
import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.MockGpsSource
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.network.TransportType
import com.uvp.sim.recording.NoopRecordingService
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.testing.asEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave 7B P1-3:[ManscdpRouterImpl] MANSCDP MESSAGE/SUBSCRIBE 入口业务级来源授权。
 *
 * codex 第二轮 audit 关键引用:
 *   "未授权 MESSAGE/SUBSCRIBE 不应先回 200 再忽略,应返回 403 或直接丢弃"
 *   "应在 200 之前 ingress 拦截"
 *
 * 行为契约:
 *  - 合法来源:进入 method dispatch,handleMessage 发 200 OK + 业务响应
 *  - 未授权来源(sourceIp 不在 allow list / From userpart 不是 serverId):
 *    **不进入 dispatch + 不发 200/403,直接 drop**(reconnaissance 防御)
 *  - Warning log 必须 emit(留审计痕迹)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManscdpIngressAuthorizationTest {

    private val platformIp = "192.168.1.100"
    private val platformServerId = "34020000002000000001"
    private val platformDomain = "3402000000"

    private fun config(allowList: List<String> = emptyList()) = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = platformIp, port = 5060,
            serverId = platformServerId, domain = platformDomain,
            allowList = allowList,
        ),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "p",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915),
    )

    private fun newRouter(
        scope: CoroutineScope,
        transport: MockSipTransport,
        cfg: SimConfig = config(),
    ): ManscdpRouterImpl {
        val deviceControlState = MutableStateFlow(DeviceControlModel())
        val tree = MutableStateFlow(CatalogTreeStore.effectiveTree(cfg))
        return ManscdpRouterImpl(
            config = cfg,
            transport = transport,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            scope = scope,
            localIpProvider = { "192.168.1.50" },
            localPortProvider = { 5060 },
            subscriptionRegistry = SubscriptionRegistry(scope),
            catalogTree = tree,
            alarmHistoryStore = AlarmHistoryStore(),
            mutableDeviceControlState = deviceControlState,
            rebootCallback = {},
            requestKeyFrameCallback = {},
            broadcastInvoker = NoopBroadcastInvoker,
            recordingService = NoopRecordingService,
            mockGps = MockGpsSource(cfg.mockPosition),
            stateRegisteredOrInCall = { true },
            identityService = com.uvp.sim.sip.DefaultSipDialogIdentityService(localIp = "192.168.1.50"),
        )
    }

    private object NoopBroadcastInvoker : BroadcastInvoker {
        override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) =
            com.uvp.sim.domain.coord.BroadcastInviteStart.Started
    }

    private fun catalogQueryMessage(
        fromUser: String = platformServerId,
        callId: String = "msg-test",
    ): SipRequest {
        val xml = "<?xml version=\"1.0\"?><Query>" +
            "<CmdType>Catalog</CmdType><SN>42</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:34020000001110000001@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:5060;branch=z9hG4bK-msg"),
                SipMessage.Header(SipHeader.FROM, "<sip:$fromUser@$platformDomain>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@$platformDomain>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "Application/MANSCDP+xml"),
                SipMessage.Header(SipHeader.CONTENT_LENGTH, xml.length.toString()),
            ),
            body = xml.encodeToByteArray(),
        )
    }

    private fun subscribeMessage(fromUser: String = platformServerId, callId: String = "sub-test"): SipRequest =
        SipRequest(
            method = SipMethod.SUBSCRIBE,
            requestUri = "sip:34020000001110000001@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:5060;branch=z9hG4bK-sub"),
                SipMessage.Header(SipHeader.FROM, "<sip:$fromUser@$platformDomain>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@$platformDomain>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
                SipMessage.Header("Event", "Catalog"),
                SipMessage.Header(SipHeader.EXPIRES, "3600"),
                SipMessage.Header(SipHeader.CONTACT, "<sip:$fromUser@$platformIp:5060>"),
            ),
        )

    // ─────────────────────────── MESSAGE 路径 ───────────────────────────

    @Test fun message_legit_platform_is_dispatched_and_responds_200() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val router = newRouter(this, transport)

        val result = router.onIncoming(catalogQueryMessage().asEnvelope(sourceIp = platformIp))
        runCurrent()

        assertEquals(RoutingResult.Handled, result)
        // 合法来源应发 200 OK
        val ok = transport.sent.filterIsInstance<SipResponse>().firstOrNull { it.statusCode == 200 }
        assertTrue(ok != null, "P1-3:合法 MANSCDP 应发 200 OK")
    }

    @Test fun message_forged_source_ip_dropped_no_200_no_403() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val router = newRouter(this, transport)

        val result = router.onIncoming(catalogQueryMessage().asEnvelope(sourceIp = "10.99.99.99"))
        runCurrent()

        assertEquals(RoutingResult.Handled, result, "未授权请求仍应被吃下,但不进 dispatch")
        // 关键断言:未授权来源不应发任何 SIP 响应(避免暴露设备存在 = reconnaissance 防御)
        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertTrue(
            responses.isEmpty(),
            "P1-3:未授权 sourceIp 必须直接 drop,不应发 200 / 403。实际 sent=${transport.sent.size}"
        )
    }

    @Test fun message_forged_from_userpart_dropped() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val router = newRouter(this, transport)

        // sourceIp 合法,但 From userpart 不是 serverId
        val result = router.onIncoming(
            catalogQueryMessage(fromUser = "99999999992000000000").asEnvelope(sourceIp = platformIp)
        )
        runCurrent()

        assertEquals(RoutingResult.Handled, result)
        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertTrue(
            responses.isEmpty(),
            "P1-3:From serverId 错也必须 drop,不发响应。实际 sent=${transport.sent.size}"
        )
    }

    @Test fun message_allow_list_permits_alternate_source() = runTest {
        val altIp = "10.0.0.50"
        val cfg = config(allowList = listOf(altIp))
        val transport = MockSipTransport(cfg)
        transport.connect()
        val router = newRouter(this, transport, cfg = cfg)

        val result = router.onIncoming(catalogQueryMessage().asEnvelope(sourceIp = altIp))
        runCurrent()

        assertEquals(RoutingResult.Handled, result)
        val ok = transport.sent.filterIsInstance<SipResponse>().firstOrNull { it.statusCode == 200 }
        assertTrue(ok != null, "P1-3:allowList 命中应放行")
    }

    // ─────────────────────────── SUBSCRIBE 路径 ───────────────────────────

    @Test fun subscribe_legit_platform_is_dispatched() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val router = newRouter(this, transport)

        val result = router.onIncoming(subscribeMessage().asEnvelope(sourceIp = platformIp))
        runCurrent()

        assertEquals(RoutingResult.Handled, result)
        // 合法 SUBSCRIBE 必有任何输出(200 OK + NOTIFY),空 sent = ingress 把它 drop 了
        assertTrue(
            transport.sent.isNotEmpty(),
            "P1-3:合法 SUBSCRIBE 应进入 dispatch(发 200 OK / NOTIFY),实际 sent=${transport.sent}"
        )
    }

    @Test fun subscribe_forged_source_ip_dropped() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val router = newRouter(this, transport)

        val result = router.onIncoming(subscribeMessage().asEnvelope(sourceIp = "10.99.99.99"))
        runCurrent()

        assertEquals(RoutingResult.Handled, result)
        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertTrue(
            responses.isEmpty(),
            "P1-3:未授权 SUBSCRIBE 来源必须 drop,不发响应"
        )
    }

    @Test fun subscribe_forged_from_user_dropped() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val router = newRouter(this, transport)

        val result = router.onIncoming(
            subscribeMessage(fromUser = "00000000001111111111").asEnvelope(sourceIp = platformIp)
        )
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertTrue(
            responses.isEmpty(),
            "P1-3:SUBSCRIBE From serverId 错也必须 drop"
        )
    }
}

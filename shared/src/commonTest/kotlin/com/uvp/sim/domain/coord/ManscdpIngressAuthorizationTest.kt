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

    private fun subscribeMessage(
        fromUser: String = platformServerId,
        callId: String = "sub-test",
        contactHeader: String? = "<sip:$fromUser@$platformIp:5060>",
        body: String = SUBSCRIBE_CATALOG_BODY,
    ): SipRequest =
        SipRequest(
            method = SipMethod.SUBSCRIBE,
            requestUri = "sip:34020000001110000001@$platformDomain",
            headers = buildList {
                add(SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:5060;branch=z9hG4bK-sub"))
                add(SipMessage.Header(SipHeader.FROM, "<sip:$fromUser@$platformDomain>;tag=plat"))
                add(SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@$platformDomain>"))
                add(SipMessage.Header(SipHeader.CALL_ID, callId))
                add(SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"))
                add(SipMessage.Header("Event", "Catalog"))
                add(SipMessage.Header(SipHeader.EXPIRES, "3600"))
                if (contactHeader != null) add(SipMessage.Header(SipHeader.CONTACT, contactHeader))
            },
            body = body.encodeToByteArray(),
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
        // ⛔ 原来这里只断言 sent.isNotEmpty() —— 而 400 也是 SipResponse,假守卫。
        // 合法 SUBSCRIBE 必须是 **200 OK**(2026-09-19 起 body 补齐为 Catalog,该断言才有意义)。
        val statuses = transport.sent.filterIsInstance<SipResponse>().map { it.statusCode }
        assertTrue(200 in statuses, "合法 SUBSCRIBE 应回 200 OK,实际状态码=$statuses")
    }

    @Test fun subscribe_withoutContact_fallsBackToTransportEndpoint_andStillSucceeds() = runTest {
        // 实测平台 `uac.go buildSubscribeRequest` 刻意不带 Contact。旧行为直接 400 ——
        // 整条 Catalog / MobilePosition / PTZPosition 订阅链路在 SIP 层毫无征兆地全废。
        val transport = MockSipTransport(config())
        transport.connect()
        val router = newRouter(this, transport)

        val result = router.onIncoming(
            subscribeMessage(contactHeader = null).asEnvelope(sourceIp = platformIp, sourcePort = 5068)
        )
        runCurrent()

        assertEquals(RoutingResult.Handled, result)
        val statuses = transport.sent.filterIsInstance<SipResponse>().map { it.statusCode }
        assertTrue(200 in statuses, "缺 Contact 时不得再回 400(Missing Contact),实际状态码=$statuses")

        // 关键:NOTIFY 的 Request-URI 必须落到**传输层来源端点**,不能用 From 的 SIP 域标识
        // (`3402000000`)去拼 —— 那样会得到一个发不出去的 URI,是更隐蔽的失败。
        val notify = transport.sent.filterIsInstance<SipRequest>().firstOrNull { it.method == SipMethod.NOTIFY }
        assertTrue(notify != null, "订阅建立后应发初始 Catalog NOTIFY")
        assertEquals("sip:$platformServerId@$platformIp:5068", notify!!.requestUri)
    }

    @Test fun subscribe_withContact_keepsUsingContact() = runTest {
        // 反面对照:有 Contact 时必须原样用它(不得被来源地址顶掉)—— Contact 可能指向
        // 与信令不同的地址,这是 UAC 的明示声明。
        val transport = MockSipTransport(config())
        transport.connect()
        val router = newRouter(this, transport)

        router.onIncoming(
            subscribeMessage(contactHeader = "<sip:$platformServerId@10.20.30.40:5090>")
                .asEnvelope(sourceIp = platformIp, sourcePort = 5068)
        )
        runCurrent()

        val notify = transport.sent.filterIsInstance<SipRequest>().firstOrNull { it.method == SipMethod.NOTIFY }
        assertEquals("sip:$platformServerId@10.20.30.40:5090", notify?.requestUri)
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

    private companion object {
        const val SUBSCRIBE_CATALOG_BODY =
            "<?xml version=\"1.0\"?><Query><CmdType>Catalog</CmdType><SN>1</SN>" +
                "<DeviceID>34020000001110000001</DeviceID></Query>"
    }
}

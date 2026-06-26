package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmHistoryStore
import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.MockGpsSource
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.network.TransportType
import com.uvp.sim.recording.NoopRecordingService
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PR3 T3.1 RED:[ManscdpRouterImpl] 直接路径覆盖。
 *
 * 这里只测 Router 独有的契约(BroadcastInvoker 反向调 / SN 池 provider 注入);
 * 8 路径完整迁移正确性走 Engine 既有 contract test
 * (SimulatorEngineAlarmTest / SimulatorEngineMediaStatusTest / CatalogSubscribeIntegrationTest
 * / AlarmSubscribeIntegrationTest 等),Engine 切换委派后这些测试不改一行继续绿
 * = Router 行为等价证明。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManscdpRouterTest {

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

    private fun newRouter(
        scope: CoroutineScope,
        transport: MockSipTransport,
        broadcastInvoker: BroadcastInvoker = NoopBroadcastInvoker,
        cseqProvider: (() -> Int)? = null,
        cseqIncrementer: (() -> Int)? = null,
    ): ManscdpRouterImpl {
        val deviceControlState = MutableStateFlow(DeviceControlState())
        val cfg = config()
        val tree = MutableStateFlow(CatalogTreeStore.effectiveTree(cfg))
        return ManscdpRouterImpl(
            config = cfg,
            transport = transport,
            scope = scope,
            localIpProvider = { "192.168.1.50" },
            localPortProvider = { 5060 },
            subscriptionRegistry = SubscriptionRegistry(scope),
            catalogTree = tree,
            alarmHistoryStore = AlarmHistoryStore(),
            mutableDeviceControlState = deviceControlState,
            rebootCallback = {},
            requestKeyFrameCallback = {},
            startUpgradeCallback = { _, _, _ -> },
            broadcastInvoker = broadcastInvoker,
            recordingService = NoopRecordingService,
            mockGps = MockGpsSource(cfg.mockPosition),
            stateRegisteredOrInCall = { true },
            cseqProvider = cseqProvider,
            cseqIncrementer = cseqIncrementer,
        )
    }

    private object NoopBroadcastInvoker : BroadcastInvoker {
        override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) {}
    }

    private fun incomingMessage(callId: String, xmlBody: String): SipRequest = SipRequest(
        method = SipMethod.MESSAGE,
        requestUri = "sip:34020000001110000001@3402000000",
        headers = listOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-msg-$callId"),
            SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=plat"),
            SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
            SipMessage.Header(SipHeader.CONTENT_TYPE, "Application/MANSCDP+xml"),
            SipMessage.Header(SipHeader.CONTENT_LENGTH, xmlBody.length.toString()),
        ),
        body = xmlBody.encodeToByteArray(),
    )

    @Test
    fun t3_1_a_handleMessage_Catalog_query_responds_CatalogResponse() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val router = newRouter(this, transport)

        val xml = "<?xml version=\"1.0\"?><Query>" +
            "<CmdType>Catalog</CmdType><SN>42</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"
        val result = router.onIncoming(incomingMessage("cat-1@plat", xml))
        runCurrent()

        assertEquals(RoutingResult.Handled, result, "Catalog 查询应被 Router 吃下")
        val responses = transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.MESSAGE }
        assertTrue(
            responses.any { it.body.decodeToString().contains("<CmdType>Catalog</CmdType>") },
            "应发出 Catalog Response MESSAGE,实际 sent=${transport.sent.size}",
        )
    }

    @Test
    fun t3_1_b_handleMessage_Broadcast_invokes_BroadcastInvoker() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        var invoked = 0
        var capturedSource = ""
        var capturedTarget = ""
        val customInvoker = object : BroadcastInvoker {
            override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) {
                invoked++
                capturedSource = sourceId
                capturedTarget = targetId
            }
        }
        val router = newRouter(this, transport, broadcastInvoker = customInvoker)

        val xml = "<?xml version=\"1.0\"?><Notify>" +
            "<CmdType>Broadcast</CmdType><SN>1</SN>" +
            "<SourceID>34020000002000000001</SourceID>" +
            "<TargetID>34020000001110000001</TargetID></Notify>"
        router.onIncoming(incomingMessage("bc-1@plat", xml))
        runCurrent()

        assertEquals(1, invoked, "Broadcast 命令必须调 BroadcastInvoker.fireBroadcastInvite")
        assertEquals("34020000002000000001", capturedSource)
        assertEquals("34020000001110000001", capturedTarget)
    }

    @Test
    fun t3_1_c_reportSnapshot_uses_injected_cseqIncrementer() = runTest {
        val transport = MockSipTransport()
        transport.connect()

        var sharedCseq = 100
        val router = newRouter(
            this, transport,
            cseqProvider = { sharedCseq },
            cseqIncrementer = { sharedCseq += 1; sharedCseq },
        )

        router.reportSnapshot()
        runCurrent()

        val msg = transport.sent.filterIsInstance<SipRequest>()
            .firstOrNull { it.method == SipMethod.MESSAGE }
        assertNotNull(msg, "reportSnapshot 应发出 MESSAGE")
        val cseqHeader = msg.firstHeader(SipHeader.CSEQ)
        assertEquals("101 MESSAGE", cseqHeader, "应用外部 SN 池(100 + 1)")
        assertEquals(101, sharedCseq, "外部 SN 池应被推进")
    }
}


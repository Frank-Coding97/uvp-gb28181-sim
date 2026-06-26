package com.uvp.sim.domain

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSubscribeIntegrationTest {

    private fun config(catalogTree: List<CatalogNode> = emptyList()) = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.1.100",
            port = 5060,
            serverId = "34020000002000000001",
            domain = "3402000000"
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
        mockPosition = GeoPoint(116.404, 39.915),
        catalogTree = catalogTree
    )

    private fun catalogSubscribeRequest(
        expires: Int? = 86400,
        callId: String = "cat-sub@plat"
    ): SipRequest {
        val body = """<?xml version="1.0"?>
<Query>
<CmdType>Catalog</CmdType>
<SN>1</SN>
<DeviceID>34020000001110000001</DeviceID>
</Query>""".encodeToByteArray()
        val headers = mutableListOf(
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-cat1"),
            SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=plat-tag"),
            SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
            SipMessage.Header(SipHeader.EVENT, "presence")
        )
        if (expires != null) headers += SipMessage.Header(SipHeader.EXPIRES, expires.toString())
        return SipRequest(
            method = SipMethod.SUBSCRIBE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = headers,
            body = body
        )
    }

    private suspend fun registerEngine(transport: MockSipTransport, engine: SimulatorEngine) {
        engine.register()
        val regReq = transport.sent.filterIsInstance<SipRequest>().first { it.method == SipMethod.REGISTER }
        transport.deliver(fakeRegister200(regReq))
    }

    private fun fakeRegister200(req: SipRequest): SipResponse {
        val headers = req.headers.filter {
            val c = SipHeader.canonicalize(it.name)
            c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.CALL_ID || c == SipHeader.CSEQ
        } + SipMessage.Header(SipHeader.TO,
            (req.toHeader() ?: "<sip:u@e>") + ";tag=server-tag")
        return SipResponse(statusCode = 200, reasonPhrase = "OK", headers = headers)
    }

    @Test
    fun catalogSubscribeReturns200AndInitialNotify() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(catalogSubscribeRequest())
        runCurrent()

        val responses = transport.sent.filterIsInstance<SipResponse>()
        assertTrue(responses.any { it.statusCode == 200 }, "Expected 200 OK for Catalog SUBSCRIBE")

        val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.NOTIFY }
        assertEquals(1, notifies.size, "Expected exactly 1 initial NOTIFY")

        val body = notifies.first().body.decodeToString()
        assertTrue(body.contains("<CmdType>Catalog</CmdType>"))
        assertTrue(body.contains("<DeviceList Num=\"2\">"), "default tree publishes 2 child nodes (root excluded)")

        val snap = engine.subscriptions.value["Catalog"]
        assertTrue(snap != null && snap.active)
        assertEquals(86400, snap!!.expiresSeconds)

        engine.shutdown()
    }

    @Test
    fun catalogSubscribeDoesNotPushPeriodicNotify() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(catalogSubscribeRequest())
        runCurrent()

        val initialCount = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(1, initialCount)

        // Catalog 不周期推送:推进 2 分钟也只有 initial 那一次
        advanceTimeBy(120_000)
        val laterCount = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(1, laterCount)

        engine.shutdown()
    }

    @Test
    fun pushCatalogNotifySendsExtraNotifyToActiveSubscribers() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(catalogSubscribeRequest())
        runCurrent()
        val countAfterSub = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(1, countAfterSub)

        engine.pushCatalogNotify()
        runCurrent()
        val countAfterPush = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(2, countAfterPush)

        engine.shutdown()
    }

    @Test
    fun pushCatalogNotifyWithoutSubscriberIsNoop() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        engine.pushCatalogNotify()
        runCurrent()

        val notifies = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(0, notifies)

        engine.shutdown()
    }

    @Test
    fun updateCatalogTreeReflectsInNextNotify() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()

        transport.deliver(catalogSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        // 推一棵新树:加一个业务分组
        val newTree = listOf(
            CatalogNode("34020000001110000001", CatalogNodeType.Device, "DEV", "34020000001110000001"),
            CatalogNode("34020000001370000001", CatalogNodeType.BusinessGroup, "新分组", "34020000001110000001"),
            CatalogNode("34020000001320000001", CatalogNodeType.VideoChannel, "新通道", "34020000001370000001")
        )
        engine.updateCatalogTree(newTree)
        runCurrent()

        val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.NOTIFY }
        assertEquals(1, notifies.size, "updateCatalogTree should push exactly one NOTIFY")
        val body = notifies.first().body.decodeToString()
        // 推送 DeviceList 不含设备根 → 3 节点树发出去是 Num=2(分组 + 通道)
        assertTrue(body.contains("<DeviceList Num=\"2\">"))
        assertTrue(body.contains("<Name>新分组</Name>"))
        assertTrue(body.contains("<Name>新通道</Name>"))

        engine.shutdown()
    }

    @Test
    fun smallChangeUsesIncrementalNotify() = runTest {
        // P1-3:小变更(单个节点改名)应走增量 NOTIFY,body 含 <Event>UPDATE</Event>
        // 起手用 10 节点的"老树",后续只改 1 个节点(变更率 1/10 = 10% < 30%)
        val rootId = "34020000001110000001"
        val largeTree = buildList {
            add(CatalogNode(rootId, CatalogNodeType.Device, "Dev", rootId))
            for (i in 1..9) {
                add(CatalogNode(
                    "video-$i", CatalogNodeType.VideoChannel,
                    "通道-$i", rootId
                ))
            }
        }
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(
            config(catalogTree = largeTree), transport, this,
            localIpProvider = { "192.168.1.50" }
        )
        registerEngine(transport, engine)
        runCurrent()

        transport.deliver(catalogSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        // 改一个节点名字
        val updated = largeTree.map {
            if (it.id == "video-3") it.copy(name = "改名通道") else it
        }
        engine.updateCatalogTree(updated)
        runCurrent()

        val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.NOTIFY }
        assertEquals(1, notifies.size)
        val body = notifies.first().body.decodeToString()
        // 增量 NOTIFY 标志
        assertTrue(body.contains("<Event>UPDATE</Event>"), "应是增量 NOTIFY")
        assertTrue(body.contains("<Name>改名通道</Name>"))
        // 增量 SumNum 是变更数,不是全树大小
        assertTrue(body.contains("<SumNum>1</SumNum>"))
        // 没改的节点不在增量 body 里
        assertTrue(!body.contains("通道-1"))

        engine.shutdown()
    }

    @Test
    fun largeChangeUsesFullNotify() = runTest {
        // P1-3:大变更(>30% 节点变化)应走全量 NOTIFY,无 <Event> 标签
        val rootId = "34020000001110000001"
        val oldTree = buildList {
            add(CatalogNode(rootId, CatalogNodeType.Device, "Dev", rootId))
            for (i in 1..3) {
                add(CatalogNode("v$i", CatalogNodeType.VideoChannel, "V$i", rootId))
            }
        }
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(
            config(catalogTree = oldTree), transport, this,
            localIpProvider = { "192.168.1.50" }
        )
        registerEngine(transport, engine)
        runCurrent()

        transport.deliver(catalogSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        // 全删 channels,只剩根 → 3/4 = 75% 变更
        val newTree = listOf(oldTree.first())
        engine.updateCatalogTree(newTree)
        runCurrent()

        val body = transport.sent.filterIsInstance<SipRequest>()
            .first { it.method == SipMethod.NOTIFY }.body.decodeToString()
        // 全量 NOTIFY 没有 Event 标签;新树只剩根,推送过滤掉根后 DeviceList 为空 SumNum=0
        assertTrue(!body.contains("<Event>"), "全量 NOTIFY 不应有 <Event> 标签")
        assertTrue(body.contains("<SumNum>0</SumNum>"))

        engine.shutdown()
    }

    @Test
    fun deletedNodeProducesDelEventInIncrementalNotify() = runTest {
        val rootId = "34020000001110000001"
        val largeTree = buildList {
            add(CatalogNode(rootId, CatalogNodeType.Device, "Dev", rootId))
            for (i in 1..9) {
                add(CatalogNode(
                    "video-$i", CatalogNodeType.VideoChannel,
                    "通道-$i", rootId
                ))
            }
        }
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(
            config(catalogTree = largeTree), transport, this,
            localIpProvider = { "192.168.1.50" }
        )
        registerEngine(transport, engine)
        runCurrent()

        transport.deliver(catalogSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        // 删一个 → 1/10 = 10% < 30%,走增量
        val newTree = largeTree.filter { it.id != "video-5" }
        engine.updateCatalogTree(newTree)
        runCurrent()

        val body = transport.sent.filterIsInstance<SipRequest>()
            .first { it.method == SipMethod.NOTIFY }.body.decodeToString()
        assertTrue(body.contains("<Event>DEL</Event>"))
        assertTrue(body.contains("<DeviceID>video-5</DeviceID>"))
        // DEL Item 没有 Name(GB §9.3.1.4 标准做法)
        assertTrue(!body.contains("通道-5"))

        engine.shutdown()
    }

    @Test
    fun noChangeProducesNoNotify() = runTest {
        // P1-3:树没变 → 不发 NOTIFY
        val rootId = "34020000001110000001"
        val tree = listOf(
            CatalogNode(rootId, CatalogNodeType.Device, "Dev", rootId),
            CatalogNode("v1", CatalogNodeType.VideoChannel, "V1", rootId)
        )
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(
            config(catalogTree = tree), transport, this,
            localIpProvider = { "192.168.1.50" }
        )
        registerEngine(transport, engine)
        runCurrent()
        transport.deliver(catalogSubscribeRequest())
        runCurrent()
        val initialNotifies = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }

        engine.updateCatalogTree(tree)  // 完全相同
        runCurrent()

        val laterNotifies = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(initialNotifies, laterNotifies, "无变更不应触发 NOTIFY")

        engine.shutdown()
    }

    @Test
    fun catalogAndMobilePositionCoexist() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        // 平台先订阅 MobilePosition
        val mpBody = """<?xml version="1.0"?>
<Query><CmdType>MobilePosition</CmdType><SN>1</SN><Interval>3</Interval></Query>"""
        val mpReq = SipRequest(
            method = SipMethod.SUBSCRIBE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-mp"),
                SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=plat-mp"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, "mp-sub@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
                SipMessage.Header(SipHeader.EVENT, "presence"),
                SipMessage.Header(SipHeader.EXPIRES, "10")
            ),
            body = mpBody.encodeToByteArray()
        )
        transport.deliver(mpReq)
        runCurrent()

        // 再订阅 Catalog
        transport.deliver(catalogSubscribeRequest())
        runCurrent()

        // 两种订阅状态都应 active
        assertTrue(engine.subscriptions.value["MobilePosition"]?.active == true)
        assertTrue(engine.subscriptions.value["Catalog"]?.active == true)

        engine.shutdown()
    }

    @Test
    fun catalogSubscribeRefreshDoesNotRepushInitial() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        transport.deliver(catalogSubscribeRequest(callId = "refresh-cat"))
        runCurrent()
        val countAfterInitial = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(1, countAfterInitial)

        // 续订(同 callId,新 expires)
        transport.deliver(catalogSubscribeRequest(expires = 7200, callId = "refresh-cat"))
        runCurrent()

        val countAfterRefresh = transport.sent.filterIsInstance<SipRequest>().count { it.method == SipMethod.NOTIFY }
        assertEquals(1, countAfterRefresh, "refresh should NOT re-send initial NOTIFY")

        engine.shutdown()
    }
}
